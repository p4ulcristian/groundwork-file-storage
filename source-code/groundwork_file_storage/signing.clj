(ns groundwork-file-storage.signing
  "AWS Signature Version 4, written here: the requests to the bucket are
   signed in their Authorization header, a signed link in its query
   string. Checked against AWS's published test suite (test-code).

   Paths are S3's: every byte but the unreserved ones (A-Z a-z 0-9 - _ .
   ~) percent-encoded once, / kept, nothing normalized. The secret key is
   handed in as text by the one caller that reveals it
   (groundwork-file-storage.bucket, groundwork-file-storage.links)."
  (:require
   [clojure.string :as str])
  (:import
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)
   (java.time Instant ZoneOffset)
   (java.time.format DateTimeFormatter)
   (java.util Locale)
   (javax.crypto Mac)
   (javax.crypto.spec SecretKeySpec)))

(def ^:private ^DateTimeFormatter amz-date-format
  (.withZone (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'" Locale/US) ZoneOffset/UTC))

(def empty-payload-hash
  "The sha256 of no bytes: the payload hash of a request without a body."
  "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

(defn turn-bytes-into-hex [^bytes bs]
  (let [hex "0123456789abcdef"
        sb  (StringBuilder. (* 2 (alength bs)))]
    (doseq [b bs]
      (let [i (bit-and b 0xff)]
        (.append sb (.charAt hex (bit-shift-right i 4)))
        (.append sb (.charAt hex (bit-and i 0xf)))))
    (str sb)))

(defn- hmac-sha256 ^bytes [^bytes signing-key ^String data]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. signing-key "HmacSHA256"))
    (.doFinal mac (.getBytes data StandardCharsets/UTF_8))))

(defn- hash-text [^String text]
  (turn-bytes-into-hex (.digest (MessageDigest/getInstance "SHA-256") (.getBytes text StandardCharsets/UTF_8))))

(defn encode-for-uri
  "RFC 3986 percent-encoding of every byte but the unreserved ones, as
   SigV4 wants; / kept when keep-slash? (a path), else encoded (a query
   key or value)."
  [^String text keep-slash?]
  (let [sb (StringBuilder.)]
    (doseq [b (.getBytes text StandardCharsets/UTF_8)
            :let [i (bit-and b 0xff)
                  c (char i)]]
      (cond
        (or (<= (int \A) i (int \Z)) (<= (int \a) i (int \z)) (<= (int \0) i (int \9))
            (contains? #{\- \_ \. \~} c)) (.append sb c)
        (and keep-slash? (= c \/))       (.append sb c)
        :else                            (.append sb (format "%%%02X" i))))
    (str sb)))

(defn write-amz-date
  "20150830T123600Z"
  [^Instant instant]
  (.format amz-date-format instant))

(defn write-query
  "The canonical query: each key and value encoded, sorted by key, then
   value, joined by &."
  [query]
  (->> query
       (map (fn [[k v]] [(encode-for-uri k false) (encode-for-uri v false)]))
       (sort)
       (map (fn [[k v]] (str k "=" v)))
       (str/join "&")))

(defn- trim-header-value
  "A header's value as SigV4 signs it: spaces around it dropped, runs of
   spaces inside made one."
  [value]
  (str/replace (str/trim (str value)) #"\s+" " "))

(defn make-canonical-request
  "SigV4's canonical request. path is the request's path as sent
   (already encoded); query is [[key value] …], not encoded; headers is
   {name value}, every header signed, host among them; payload-hash is
   the hex sha256 of the body, or UNSIGNED-PAYLOAD."
  [{:keys [method path query headers payload-hash]}]
  (let [signed (sort-by first (map (fn [[k v]] [(str/lower-case k) (trim-header-value v)]) headers))]
    (str/join "\n" [method
                    path
                    (write-query query)
                    (str/join (map (fn [[k v]] (str k ":" v "\n")) signed))
                    (str/join ";" (map first signed))
                    payload-hash])))

(defn- write-signed-header-names [headers]
  (str/join ";" (sort (map str/lower-case (keys headers)))))

(defn- make-scope [day region service]
  (str day "/" region "/" service "/aws4_request"))

(defn make-signature
  "The hex signature of a canonical request, signed at amz-date for the
   region and service by the secret key."
  [canonical-request {:keys [secret-key region service]} amz-date]
  (let [day         (subs amz-date 0 8)
        to-sign     (str/join "\n" ["AWS4-HMAC-SHA256" amz-date (make-scope day region service) (hash-text canonical-request)])
        signing-key (-> (.getBytes (str "AWS4" secret-key) StandardCharsets/UTF_8)
                        (hmac-sha256 day)
                        (hmac-sha256 region)
                        (hmac-sha256 service)
                        (hmac-sha256 "aws4_request"))]
    (turn-bytes-into-hex (hmac-sha256 signing-key to-sign))))

(defn sign-request
  "The headers to send with a request, signed in its Authorization header:
   the request's own headers (host among them) with x-amz-date and
   authorization added. The caller adds x-amz-content-sha256 to the
   headers it signs when the service needs it (S3 does)."
  [{:keys [method path query headers payload-hash]} {:keys [access-key region service] :as credentials} ^Instant now]
  (let [amz-date (write-amz-date now)
        headers  (assoc headers "x-amz-date" amz-date)
        signature (make-signature (make-canonical-request {:method method :path path :query query
                                                           :headers headers :payload-hash payload-hash})
                                  credentials amz-date)]
    (assoc headers "authorization"
           (str "AWS4-HMAC-SHA256 Credential=" access-key "/" (make-scope (subs amz-date 0 8) region service)
                ", SignedHeaders=" (write-signed-header-names headers)
                ", Signature=" signature))))

(defn presign-query
  "The query of a link signed in its query string, X-Amz-Signature last:
   the request's own query with SigV4's X-Amz-* keys, valid for
   expires-seconds after signed-at."
  [{:keys [method path query headers payload-hash expires-seconds]} {:keys [access-key region service] :as credentials}
   ^Instant signed-at]
  (let [amz-date  (write-amz-date signed-at)
        query     (concat query
                          [["X-Amz-Algorithm" "AWS4-HMAC-SHA256"]
                           ["X-Amz-Credential" (str access-key "/" (make-scope (subs amz-date 0 8) region service))]
                           ["X-Amz-Date" amz-date]
                           ["X-Amz-Expires" (str expires-seconds)]
                           ["X-Amz-SignedHeaders" (write-signed-header-names headers)]])
        signature (make-signature (make-canonical-request {:method method :path path :query query
                                                           :headers headers :payload-hash payload-hash})
                                  credentials amz-date)]
    (str (write-query query) "&X-Amz-Signature=" signature)))
