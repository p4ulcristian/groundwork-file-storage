(ns groundwork-file-storage.bucket
  "The bucket, through the JDK's own http client (java.net.http), every
   request signed by SigV4 (groundwork-file-storage.signing), path-style
   at the settings' :endpoint: <endpoint>/<bucket>/<key>. An object is put
   once (If-None-Match: *, never written over), from bytes or streamed
   from a file on disk with its known length; read; copied by the bucket
   from another bucket of the same endpoint; deleted; listed.

   Every request carries the body's sha256 (x-amz-content-sha256), which
   the bucket checks: a file that changed between its hashing and its put
   is refused. The client connects within the settings'
   :connect-timeout-ms, waits :request-timeout-ms for an answer, and never
   follows a redirect. A request the bucket was too busy for (429, 503,
   504), or that never connected (nothing was sent), is sent again by the
   settings' :retries; a body from a file is read again from the file."
  (:require
   [clojure.string :as str]
   [groundwork-file-storage.signing :as signing]
   [groundwork-settings.api :as groundwork-settings])
  (:import
   (java.io ByteArrayInputStream InputStream)
   (java.net ConnectException URI)
   (java.net.http HttpClient HttpClient$Redirect HttpClient$Version HttpConnectTimeoutException HttpRequest
                  HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers)
   (java.nio.file Path)
   (java.time Duration Instant)
   (javax.xml.parsers DocumentBuilderFactory)))

;; -----------------------------------------------------------------------------
;; The client and sending

(defn make-http-client
  "The JDK's http client: its connect timeout the settings', HTTP/1.1,
   redirects never followed. The store's one client, for the bucket and
   for the cache purge."
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
        (do (when (instance? java.io.Closeable (:body answer)) (.close ^java.io.Closeable (:body answer)))
            (Thread/sleep (long wait))
            (recur (inc retries)))
        answer))))

;; -----------------------------------------------------------------------------
;; A request to the bucket

(defn- find-host
  "The Host header the JDK's client sends: the port only when it isn't
   the scheme's own."
  [^URI uri]
  (let [port (.getPort uri)]
    (if (or (neg? port) (and (= "https" (.getScheme uri)) (= 443 port)) (and (= "http" (.getScheme uri)) (= 80 port)))
      (.getHost uri)
      (str (.getHost uri) ":" port))))

(defn write-object-path
  "/<bucket>/<key>, each encoded as SigV4 wants."
  [bucket object-key]
  (str "/" (signing/encode-for-uri bucket false) (when object-key (str "/" (signing/encode-for-uri object-key true)))))

(defn find-endpoint-parts
  "The endpoint's scheme and Host header: {:scheme \"https\" :host \"x.r2.cloudflarestorage.com\"}."
  [endpoint]
  (let [uri (URI. endpoint)]
    {:scheme (.getScheme uri) :host (find-host uri)}))

(defn reveal-credentials
  "The bucket's key, revealed here for SigV4, and its region."
  [{:keys [access-key secret-key region]}]
  {:access-key (groundwork-settings/reveal access-key)
   :secret-key (groundwork-settings/reveal secret-key)
   :region     region
   :service    "s3"})

(defn- make-request-fn
  "(fn [] request): a fresh signed request each time, so a retry is
   signed anew. body is nil, bytes, or a java.nio.file.Path."
  [settings {:keys [method path query headers body payload-hash]}]
  (let [{:keys [scheme host]} (find-endpoint-parts (:endpoint settings))
        credentials           (reveal-credentials settings)
        query-text            (when (seq query) (signing/write-query query))]
    (fn []
      (let [signed  (signing/sign-request {:method       method
                                           :path         path
                                           :query        query
                                           :headers      (assoc headers "host" host "x-amz-content-sha256" payload-hash)
                                           :payload-hash payload-hash}
                                          credentials (Instant/now))
            builder (-> (HttpRequest/newBuilder (URI. (str scheme "://" host path (when query-text (str "?" query-text)))))
                        (.timeout (Duration/ofMillis (:request-timeout-ms settings)))
                        (.method method (cond
                                          (nil? body)            (HttpRequest$BodyPublishers/noBody)
                                          (bytes? body)          (HttpRequest$BodyPublishers/ofByteArray body)
                                          (instance? Path body)  (HttpRequest$BodyPublishers/ofFile body))))]
        (doseq [[k v] (dissoc signed "host")]
          (.header builder k v))
        (.build builder)))))

(defn send-to-bucket!
  "Sends a signed request to the bucket, with retries; {:status :headers
   :body}. body-kind :bytes reads the answer's body as bytes, :stream as
   an InputStream the caller closes."
  [client settings request body-kind]
  (send-with-retries! client (:retries settings) (make-request-fn settings request)
                      (case body-kind
                        :bytes  (HttpResponse$BodyHandlers/ofByteArray)
                        :stream (HttpResponse$BodyHandlers/ofInputStream))))

;; -----------------------------------------------------------------------------
;; What the bucket answers

(defn- read-xml ^org.w3c.dom.Document [^bytes bs]
  (let [factory (doto (DocumentBuilderFactory/newInstance)
                  (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true)
                  (.setExpandEntityReferences false)
                  (.setNamespaceAware false))]
    (.parse (.newDocumentBuilder factory) (ByteArrayInputStream. bs))))

(defn- find-texts
  "The text of every element of this name in the document."
  [^org.w3c.dom.Document document ^String tag]
  (let [^org.w3c.dom.NodeList nodes (.getElementsByTagName document tag)]
    (vec (for [i (range (.getLength nodes))] (.getTextContent (.item nodes (int i)))))))

(defn- read-error
  "The bucket's error code and message from an answer's body, when it is
   an S3 <Error>; nils otherwise."
  [^bytes body]
  (let [text (when (and body (pos? (alength body))) (String. body "UTF-8"))]
    (if (and text (str/includes? text "<Error>"))
      (let [document (try (read-xml body) (catch Exception _ nil))]
        {:code    (some-> document (find-texts "Code") first)
         :message (some-> document (find-texts "Message") first)})
      {:code nil :message nil})))

(defn- throw-failure
  "Throws :groundwork-file-storage/bucket-failed for an answer, or for an
   exception (the request never got one)."
  [what object-key answer-or-exception]
  (if (instance? Throwable answer-or-exception)
    (throw (ex-info (str "The bucket couldn't " what " " object-key ": " (ex-message answer-or-exception))
                    {:type :groundwork-file-storage/bucket-failed :object-key object-key
                     :status nil :code nil :message (ex-message answer-or-exception)}
                    answer-or-exception))
    (let [{:keys [status body]} answer-or-exception
          failure (assoc (read-error (when (bytes? body) body)) :status status)]
      (throw (ex-info (str "The bucket couldn't " what " " object-key ": " (pr-str failure))
                      (assoc failure :type :groundwork-file-storage/bucket-failed :object-key object-key))))))

(defmacro ^:private answering
  "Runs body; an exception that isn't already the bucket's failure is
   thrown as one."
  [what object-key & body]
  `(try ~@body
        (catch clojure.lang.ExceptionInfo e#
          (if (= :groundwork-file-storage/bucket-failed (:type (ex-data e#))) (throw e#) (throw-failure ~what ~object-key e#)))
        (catch Exception e# (throw-failure ~what ~object-key e#))))

;; -----------------------------------------------------------------------------
;; The operations

(defn put-object-once!
  "Puts the content at the key unless the bucket has an object there
   (If-None-Match: *, so an object is never written over): content is
   bytes, or a java.nio.file.Path streamed from the file with its known
   length. sha256 is the content's, which the bucket checks. A
   content-disposition (or nil) is stored with the object and sent with
   it. Returns :put, or :there-already; throws
   :groundwork-file-storage/bucket-failed."
  [client settings object-key content sha256 content-type content-disposition]
  (answering "store" object-key
             (let [answer (send-to-bucket! client settings
                                           {:method       "PUT"
                                            :path         (write-object-path (:bucket settings) object-key)
                                            :query        []
                                            :headers      (cond-> {"content-type" content-type "if-none-match" "*"}
                                                            content-disposition (assoc "content-disposition" content-disposition))
                                            :body         content
                                            :payload-hash sha256}
                                           :bytes)]
               (case (long (:status answer))
                 (200 201) :put
                 412       :there-already
                 (throw-failure "store" object-key answer)))))

(defn copy-object-once!
  "Has the bucket copy the object at the key from another bucket of the
   same endpoint to the store's bucket, the same key (S3's CopyObject,
   x-amz-copy-source): the bytes never pass through the server. The
   object gets this content type and content-disposition (or nil), not
   the source's (x-amz-metadata-directive: REPLACE), and is never written
   over (If-None-Match: *). The store's key must read the source bucket.
   Returns :copied or :there-already; throws
   :groundwork-file-storage/bucket-failed (a source with no object at the
   key too)."
  [client settings from-bucket object-key content-type content-disposition]
  (answering "copy" object-key
             (let [answer (send-to-bucket! client settings
                                           {:method       "PUT"
                                            :path         (write-object-path (:bucket settings) object-key)
                                            :query        []
                                            :headers      (cond-> {"content-type"             content-type
                                                                   "if-none-match"            "*"
                                                                   "x-amz-copy-source"        (write-object-path from-bucket object-key)
                                                                   "x-amz-metadata-directive" "REPLACE"}
                                                            content-disposition (assoc "content-disposition" content-disposition))
                                            :body         nil
                                            :payload-hash signing/empty-payload-hash}
                                           :bytes)
          ;; CopyObject may answer 200 with an <Error> in its body
                   {:keys [code]} (read-error (:body answer))]
               (cond
                 (= 412 (:status answer))                  :there-already
                 (and (= 200 (:status answer)) (nil? code)) :copied
                 :else                                     (throw-failure "copy" object-key answer)))))

(defn read-object-bytes
  "The object's bytes, or nil when the bucket has no object at the key;
   throws :groundwork-file-storage/bucket-failed."
  [client settings object-key]
  (answering "read" object-key
             (let [answer (send-to-bucket! client settings
                                           {:method "GET" :path (write-object-path (:bucket settings) object-key) :query []
                                            :headers {} :body nil :payload-hash signing/empty-payload-hash}
                                           :stream)]
               (with-open [in ^InputStream (:body answer)]
                 (case (long (:status answer))
                   200 (.readAllBytes in)
                   404 nil
                   (throw-failure "read" object-key (assoc answer :body (.readAllBytes in))))))))

(defn delete-object!
  "Deletes the object at the key; the bucket says the same whether it had
   one or not. Throws :groundwork-file-storage/bucket-failed."
  [client settings object-key]
  (answering "delete" object-key
             (let [answer (send-to-bucket! client settings
                                           {:method "DELETE" :path (write-object-path (:bucket settings) object-key) :query []
                                            :headers {} :body nil :payload-hash signing/empty-payload-hash}
                                           :bytes)]
               (if (contains? #{200 204} (:status answer))
                 :deleted
                 (throw-failure "delete" object-key answer)))))

(defn list-object-keys
  "Every key in the bucket that starts with the prefix, by S3's
   ListObjectsV2, a page at a time (what the bucket holds, not what the
   files table records). Throws :groundwork-file-storage/bucket-failed."
  [client settings key-prefix]
  (answering "list" key-prefix
             (loop [token nil keys []]
               (let [answer   (send-to-bucket! client settings
                                               {:method       "GET"
                                                :path         (write-object-path (:bucket settings) nil)
                                                :query        (cond-> [["list-type" "2"] ["prefix" key-prefix]]
                                                                token (conj ["continuation-token" token]))
                                                :headers      {}
                                                :body         nil
                                                :payload-hash signing/empty-payload-hash}
                                               :bytes)
                     _        (when-not (= 200 (:status answer)) (throw-failure "list" key-prefix answer))
                     document (read-xml (:body answer))
                     keys     (into keys (find-texts document "Key"))
                     next     (first (find-texts document "NextContinuationToken"))]
                 (if (and (= "true" (first (find-texts document "IsTruncated"))) next)
                   (recur next keys)
                   keys)))))
