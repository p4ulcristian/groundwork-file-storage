(ns groundwork-file-storage.cache-purge
  "A deleted public file's address purged from Cloudflare's cache, so the
   cache stops serving it:

     POST <:api-url>/zones/<:zone-id>/purge_cache
     Authorization: Bearer <:api-token>
     {\"files\":[\"https://files.shop.example/shops/photos/….png\"]}

   Cloudflare answers {\"success\":true,…}. A REST call of one POST, sent
   through the JDK's http client (the store's one for its purges), with
   the settings' :connect-timeout-ms, :request-timeout-ms and :retries."
  (:require
   [clojure.string :as str]
   [groundwork-settings.api :as groundwork-settings]
   [jsonista.core :as json])
  (:import
   (java.net ConnectException URI)
   (java.net.http HttpClient HttpClient$Redirect HttpClient$Version HttpConnectTimeoutException HttpRequest
                  HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers)
   (java.time Duration)))

(defn make-http-client
  "The JDK's http client: its connect timeout the settings', HTTP/1.1,
   redirects never followed. The store's one client for the cache purge."
  [{:keys [connect-timeout-ms]}]
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofMillis connect-timeout-ms))
      (.followRedirects HttpClient$Redirect/NEVER)
      (.version HttpClient$Version/HTTP_1_1)
      (.build)))

(defn stop-http-client! [^HttpClient client]
  (.close client))

(defn- did-never-connect?
  "Whether the request never reached the other side: the connection timed
   out or was refused."
  [^Throwable e]
  (loop [^Throwable t e]
    (cond
      (nil? t) false
      (or (instance? HttpConnectTimeoutException t) (instance? ConnectException t)) true
      :else (recur (.getCause t)))))

(def ^:private busy-statuses
  "The answers that say: send it again later."
  #{429 503 504})

(defn send-with-retries!
  "Sends (make-request), a fresh java.net.http request each time, and
   returns {:status :headers :body}, the body as the handler reads it.
   A busy answer, or a request that never connected, is sent again by
   :retries {:max-retries :first-wait-ms :max-wait-ms}: waiting
   :first-wait-ms, twice as long each time, never longer than
   :max-wait-ms. The last busy answer is returned; the last failure to
   connect is thrown."
  [^HttpClient client {:keys [max-retries first-wait-ms max-wait-ms]} make-request body-handler]
  (loop [retries 0]
    (let [wait   (when (< retries max-retries)
                   (min max-wait-ms (* first-wait-ms (bit-shift-left 1 retries))))
          answer (try
                   (let [^HttpResponse response (.send client ^HttpRequest (make-request) body-handler)]
                     {:status  (.statusCode response)
                      :headers (into {} (map (fn [[k vs]] [(str/lower-case k) (first vs)])) (.map (.headers response)))
                      :body    (.body response)})
                   (catch Exception e
                     (if (and wait (did-never-connect? e)) ::again (throw e))))]
      (if (or (= ::again answer) (and wait (contains? busy-statuses (:status answer))))
        (do (Thread/sleep (long wait))
            (recur (inc retries)))
        answer))))

(defn purge-address!
  "Purges the address from the cache. Returns :purged; throws
   :groundwork-file-storage/cache-purge-failed with Cloudflare's status and
   errors when it isn't."
  [client {:keys [cache-purge request-timeout-ms retries]} address]
  (let [{:keys [api-url zone-id api-token]} cache-purge
        body    (json/write-value-as-string {"files" [address]})
        failed  (fn [data cause]
                  (ex-info (str "Cloudflare's cache didn't purge " address ": " (pr-str data))
                           (assoc data :type :groundwork-file-storage/cache-purge-failed :address address)
                           cause))
        answer  (try
                  (send-with-retries!
                   client retries
                   (fn []
                     (-> (HttpRequest/newBuilder (URI. (str api-url "/zones/" zone-id "/purge_cache")))
                         (.timeout (Duration/ofMillis request-timeout-ms))
                         (.header "content-type" "application/json")
                         (.header "authorization" (str "Bearer " (groundwork-settings/reveal api-token)))
                         (.POST (HttpRequest$BodyPublishers/ofString body))
                         (.build)))
                   (HttpResponse$BodyHandlers/ofString))
                  (catch Exception e
                    (throw (failed {:status nil :errors (ex-message e)} e))))
        read    (try (json/read-value (:body answer)) (catch Exception _ nil))]
    (if (and (= 200 (:status answer)) (true? (get read "success")))
      :purged
      (throw (failed {:status (:status answer) :errors (get read "errors")} nil)))))
