(ns groundwork-file-storage.cache-purge
  "A deleted public file's address purged from Cloudflare's cache, so the
   cache stops serving it:

     POST <:api-url>/zones/<:zone-id>/purge_cache
     Authorization: Bearer <:api-token>
     {\"files\":[\"https://files.shop.example/shops/photos/….png\"]}

   Cloudflare answers {\"success\":true,…}. Sent through the store's one
   http client, with the settings' :request-timeout-ms and :retries."
  (:require
   [groundwork-file-storage.bucket :as bucket]
   [groundwork-settings.api :as groundwork-settings]
   [jsonista.core :as json])
  (:import
   (java.net URI)
   (java.net.http HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
   (java.time Duration)))

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
                  (bucket/send-with-retries!
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
