(ns groundwork-file-storage.bucket
  "The bucket, through the AWS SDK for Java v2 (software.amazon.awssdk's
   S3Client on its Apache 5 http client), path-style at the settings'
   :endpoint: <endpoint>/<bucket>/<key>. An object is put once
   (If-None-Match: *, never written over), from bytes or streamed from a
   file on disk with its known length (RequestBody.fromFile); read; copied
   by the bucket from another bucket of the same endpoint (CopyObject);
   deleted; listed (ListObjectsV2).

   Set for R2 as R2's own SDK page says: path-style, chunked encoding off,
   the settings' :region (R2: \"auto\"); request checksums and response
   checksum checks only where an operation requires them (WHEN_REQUIRED),
   as R2 takes none of the SDK's newer default ones. A put carries the
   Content-MD5 of the bytes hashed for its key, which the bucket checks: a
   file that changed between its hashing and its put is refused.

   The client connects within the settings' :connect-timeout-ms, and an
   attempt (connecting, sending, the answer) lasts at most
   :request-timeout-ms; the SDK's client never follows a redirect. A
   request the bucket was too busy for (429, 503, 504), or that never
   connected (nothing was sent), is sent again by the settings' :retries,
   the SDK's standard retry strategy with exactly that rule: no other
   retries, no jitter, no circuit breaker; a body from a file is read
   again from the file. No ~/.aws profile file is read."
  (:require
   [groundwork-settings.api :as groundwork-settings])
  (:import
   (java.net ConnectException URI)
   (java.nio.file Path)
   (java.time Clock Duration Instant ZoneOffset)
   (java.util Base64)
   (org.apache.hc.client5.http ConnectTimeoutException)
   (software.amazon.awssdk.auth.credentials AwsBasicCredentials AwsCredentialsProvider)
   (software.amazon.awssdk.core.checksums RequestChecksumCalculation ResponseChecksumValidation)
   (software.amazon.awssdk.core.client.config ClientOverrideConfiguration)
   (software.amazon.awssdk.core.exception SdkServiceException)
   (software.amazon.awssdk.core.signer Presigner Signer)
   (software.amazon.awssdk.core.sync RequestBody)
   (software.amazon.awssdk.awscore AwsRequestOverrideConfiguration)
   (software.amazon.awssdk.awscore.exception AwsServiceException)
   (software.amazon.awssdk.http SdkHttpFullRequest SdkHttpRequest)
   (software.amazon.awssdk.http.apache5 Apache5HttpClient)
   (software.amazon.awssdk.http.auth.aws.signer AwsV4FamilyHttpSigner AwsV4FamilyHttpSigner$AuthLocation AwsV4HttpSigner)
   (software.amazon.awssdk.http.auth.spi.signer HttpSigner SignRequest SignedRequest)
   (software.amazon.awssdk.identity.spi AwsCredentialsIdentity)
   (software.amazon.awssdk.profiles ProfileFile)
   (software.amazon.awssdk.regions Region)
   (software.amazon.awssdk.retries StandardRetryStrategy)
   (software.amazon.awssdk.retries.api BackoffStrategy RetryStrategy)
   (software.amazon.awssdk.services.s3 S3Client S3ClientBuilder S3Configuration)
   (software.amazon.awssdk.services.s3.model CopyObjectRequest CreateBucketRequest DeleteObjectRequest GetObjectRequest
                                             ListObjectsV2Request MetadataDirective PutObjectRequest S3Object)
   (software.amazon.awssdk.services.s3.presigner S3Presigner)
   (software.amazon.awssdk.services.s3.presigner.model GetObjectPresignRequest)))

;; -----------------------------------------------------------------------------
;; The client

(defn- make-credentials-provider
  "The bucket's key, revealed each time the SDK asks for it: for each
   request and each link it signs."
  ^AwsCredentialsProvider [{:keys [access-key secret-key]}]
  (reify AwsCredentialsProvider
    (resolveCredentials [_]
      (AwsBasicCredentials/create (groundwork-settings/reveal access-key) (groundwork-settings/reveal secret-key)))))

(def ^:private busy-statuses
  "The answers that say: send it again later."
  #{429 503 504})

(defn- did-never-connect?
  "Whether the request never reached the other side: the connection timed
   out or was refused."
  [^Throwable e]
  (loop [^Throwable t e]
    (cond
      (nil? t) false
      (or (instance? ConnectTimeoutException t) (instance? ConnectException t)) true
      :else (recur (.getCause t)))))

(defn- should-send-again? [^Throwable e]
  (boolean (or (and (instance? SdkServiceException e) (contains? busy-statuses (.statusCode ^SdkServiceException e)))
               (did-never-connect? e))))

(defn- make-retry-strategy
  "The settings' :retries as the SDK's standard retry strategy: at most
   :max-retries more attempts, the first after :first-wait-ms, twice as
   long each time, never longer than :max-wait-ms; only a busy answer or a
   request that never connected."
  ^RetryStrategy [{:keys [max-retries first-wait-ms max-wait-ms]}]
  (let [backoff (BackoffStrategy/exponentialDelayWithoutJitter (Duration/ofMillis first-wait-ms) (Duration/ofMillis max-wait-ms))
        ^software.amazon.awssdk.retries.StandardRetryStrategy$Builder builder (StandardRetryStrategy/builder)]
    (.maxAttempts builder (int (inc max-retries)))
    (.backoffStrategy builder backoff)
    (.throttlingBackoffStrategy builder backoff)
    (.treatAsThrottling builder (reify java.util.function.Predicate (test [_ _] false)))
    (.circuitBreakerEnabled builder false)
    (.retryOnException builder (reify java.util.function.Predicate (test [_ e] (should-send-again? e))))
    (.build builder)))

(def ^:private no-profile-file
  "No ~/.aws/config or credentials file: every value is the settings'."
  (delay (.build (ProfileFile/aggregator))))

(defn- make-s3-configuration
  "R2's settings (R2's SDK page): path-style, chunked encoding off."
  ^S3Configuration []
  (let [builder (S3Configuration/builder)]
    (.pathStyleAccessEnabled builder true)
    (.chunkedEncodingEnabled builder false)
    (.profileFile builder ^ProfileFile @no-profile-file)
    (.build builder)))

(defn make-client
  "The store's S3 client by its settings: :endpoint, :region, the key,
   R2's settings, checksums only where required, the timeouts and the
   retries."
  ^S3Client [{:keys [endpoint region connect-timeout-ms request-timeout-ms retries] :as settings}]
  (let [^S3ClientBuilder builder (S3Client/builder)
        http                     (Apache5HttpClient/builder)
        override                 (ClientOverrideConfiguration/builder)]
    (.connectionTimeout http (Duration/ofMillis connect-timeout-ms))
    (.socketTimeout http (Duration/ofMillis request-timeout-ms))
    (.retryStrategy override (make-retry-strategy retries))
    (.apiCallAttemptTimeout override (Duration/ofMillis request-timeout-ms))
    (.defaultProfileFile override ^ProfileFile @no-profile-file)
    (.endpointOverride builder (URI. endpoint))
    (.region builder (Region/of region))
    (.credentialsProvider builder (make-credentials-provider settings))
    (.serviceConfiguration builder (make-s3-configuration))
    (.requestChecksumCalculation builder RequestChecksumCalculation/WHEN_REQUIRED)
    (.responseChecksumValidation builder ResponseChecksumValidation/WHEN_REQUIRED)
    (.httpClientBuilder builder http)
    (.overrideConfiguration builder ^ClientOverrideConfiguration (.build override))
    (.build builder)))

(defn make-presigner
  "The store's S3Presigner: the same endpoint, region and key, path-style."
  ^S3Presigner [{:keys [endpoint region] :as settings}]
  (let [builder (S3Presigner/builder)]
    (.endpointOverride builder (URI. endpoint))
    (.region builder (Region/of region))
    (.credentialsProvider builder (make-credentials-provider settings))
    (.serviceConfiguration builder (make-s3-configuration))
    (.build builder)))

(defn stop-client! [^java.lang.AutoCloseable client]
  (.close client))

;; -----------------------------------------------------------------------------
;; Failures

(defn- find-status [^Throwable e]
  (when (instance? SdkServiceException e) (.statusCode ^SdkServiceException e)))

(defn- throw-failure
  "Throws :groundwork-file-storage/bucket-failed for an exception: the
   bucket's status, error code and message when it answered."
  [what object-key ^Throwable e]
  (let [details (when (instance? AwsServiceException e) (.awsErrorDetails ^AwsServiceException e))
        failure {:status  (find-status e)
                 :code    (some-> details (.errorCode))
                 :message (or (some-> details (.errorMessage)) (ex-message e))}]
    (throw (ex-info (str "The bucket couldn't " what " " object-key ": " (pr-str failure))
                    (assoc failure :type :groundwork-file-storage/bucket-failed :object-key object-key)
                    e))))

;; -----------------------------------------------------------------------------
;; The operations

(defn turn-hex-into-base64
  "A digest's hex as base64, as Content-MD5 carries it."
  [^String hex]
  (.encodeToString (Base64/getEncoder) (.parseHex (java.util.HexFormat/of) hex)))

(defn put-object-once!
  "Puts the content at the key unless the bucket has an object there
   (If-None-Match: *, so an object is never written over): content is
   bytes, or a java.nio.file.Path streamed from the file with its known
   length. md5 is the hex MD5 of the bytes hashed for the key, sent as
   Content-MD5, which the bucket checks. A content-disposition (or nil) is
   stored with the object and sent with it. Returns :put, or
   :there-already; throws :groundwork-file-storage/bucket-failed."
  [^S3Client client settings object-key content md5 content-type content-disposition]
  (let [builder (PutObjectRequest/builder)]
    (.bucket builder ^String (:bucket settings))
    (.key builder ^String object-key)
    (.contentType builder ^String content-type)
    (.contentDisposition builder ^String content-disposition)
    (.contentMD5 builder ^String (turn-hex-into-base64 md5))
    (.ifNoneMatch builder "*")
    (try
      (.putObject client ^PutObjectRequest (.build builder)
                  (if (instance? Path content)
                    (RequestBody/fromFile ^Path content)
                    (RequestBody/fromBytes ^bytes content)))
      :put
      (catch Exception e
        (if (= 412 (find-status e)) :there-already (throw-failure "store" object-key e))))))

(defn copy-object-once!
  "Has the bucket copy the object at the key from another bucket of the
   same endpoint to the store's bucket, the same key (S3's CopyObject):
   the bytes never pass through the server. The object gets this content
   type and content-disposition (or nil), not the source's
   (MetadataDirective REPLACE), and is never written over
   (If-None-Match: *). The store's key must read the source bucket.
   Returns :copied or :there-already; throws
   :groundwork-file-storage/bucket-failed (a source with no object at the
   key too; an error CopyObject answers inside a 200 too, which the SDK
   reads)."
  [^S3Client client settings from-bucket object-key content-type content-disposition]
  (let [builder (CopyObjectRequest/builder)]
    (.sourceBucket builder ^String from-bucket)
    (.sourceKey builder ^String object-key)
    (.destinationBucket builder ^String (:bucket settings))
    (.destinationKey builder ^String object-key)
    (.contentType builder ^String content-type)
    (.contentDisposition builder ^String content-disposition)
    (.metadataDirective builder MetadataDirective/REPLACE)
    (.ifNoneMatch builder "*")
    (try
      (.copyObject client ^CopyObjectRequest (.build builder))
      :copied
      (catch Exception e
        (if (= 412 (find-status e)) :there-already (throw-failure "copy" object-key e))))))

(defn read-object-bytes
  "The object's bytes, or nil when the bucket has no object at the key
   (404); throws :groundwork-file-storage/bucket-failed."
  [^S3Client client settings object-key]
  (let [builder (GetObjectRequest/builder)]
    (.bucket builder ^String (:bucket settings))
    (.key builder ^String object-key)
    (try
      (.asByteArray (.getObjectAsBytes client ^GetObjectRequest (.build builder)))
      (catch Exception e
        (if (= 404 (find-status e)) nil (throw-failure "read" object-key e))))))

(defn delete-object!
  "Deletes the object at the key; the bucket says the same whether it had
   one or not. Throws :groundwork-file-storage/bucket-failed."
  [^S3Client client settings object-key]
  (let [builder (DeleteObjectRequest/builder)]
    (.bucket builder ^String (:bucket settings))
    (.key builder ^String object-key)
    (try
      (.deleteObject client ^DeleteObjectRequest (.build builder))
      :deleted
      (catch Exception e (throw-failure "delete" object-key e)))))

(defn list-object-keys
  "Every key in the bucket that starts with the prefix, by S3's
   ListObjectsV2, a page at a time (what the bucket holds, not what the
   files table records). Throws :groundwork-file-storage/bucket-failed."
  [^S3Client client settings key-prefix]
  (let [builder (ListObjectsV2Request/builder)]
    (.bucket builder ^String (:bucket settings))
    (.prefix builder ^String key-prefix)
    (try
      (mapv #(.key ^S3Object %) (.contents (.listObjectsV2Paginator client ^ListObjectsV2Request (.build builder))))
      (catch Exception e (throw-failure "list" key-prefix e)))))

(defn make-bucket!
  "Makes the store's bucket, unless the key's account has it already
   (409): for tests and a developer's first start. Returns :made or
   :there-already; throws :groundwork-file-storage/bucket-failed."
  [^S3Client client settings]
  (let [builder (CreateBucketRequest/builder)]
    (.bucket builder ^String (:bucket settings))
    (try
      (.createBucket client ^CreateBucketRequest (.build builder))
      :made
      (catch Exception e
        (if (= 409 (find-status e)) :there-already (throw-failure "make the bucket" (:bucket settings) e))))))

;; -----------------------------------------------------------------------------
;; Signed links

(defn- sign-in-query
  "The request S3Presigner made, signed by the SDK's SigV4 signer
   (AwsV4HttpSigner) in its query string at signed-at for lifetime, as
   S3Presigner itself signs: no payload, no path normalizing, no double
   encoding."
  ^SdkHttpFullRequest [^AwsCredentialsProvider credentials-provider settings ^SdkHttpFullRequest request ^Instant signed-at ^Duration lifetime]
  (let [sign-request (SignRequest/builder ^AwsCredentialsIdentity (.resolveCredentials credentials-provider))]
    (.request sign-request request)
    (.putProperty sign-request AwsV4HttpSigner/SERVICE_SIGNING_NAME "s3")
    (.putProperty sign-request AwsV4HttpSigner/REGION_NAME (:region settings))
    (.putProperty sign-request AwsV4FamilyHttpSigner/AUTH_LOCATION AwsV4FamilyHttpSigner$AuthLocation/QUERY_STRING)
    (.putProperty sign-request AwsV4FamilyHttpSigner/EXPIRATION_DURATION lifetime)
    (.putProperty sign-request AwsV4FamilyHttpSigner/PAYLOAD_SIGNING_ENABLED false)
    (.putProperty sign-request AwsV4FamilyHttpSigner/NORMALIZE_PATH false)
    (.putProperty sign-request AwsV4FamilyHttpSigner/DOUBLE_URL_ENCODE false)
    (.putProperty sign-request HttpSigner/SIGNING_CLOCK (Clock/fixed signed-at ZoneOffset/UTC))
    (let [^SignedRequest signed          (.sign ^AwsV4HttpSigner (AwsV4HttpSigner/create) ^SignRequest (.build sign-request))
          ^SdkHttpRequest signed-request (.request signed)
          builder                        (SdkHttpFullRequest/builder)]
      (.protocol builder (.protocol signed-request))
      (.method builder (.method signed-request))
      (.host builder (.host signed-request))
      (.port builder (Integer/valueOf (.port signed-request)))
      (.encodedPath builder (.encodedPath signed-request))
      (.headers builder (.headers signed-request))
      (.rawQueryParameters builder (.rawQueryParameters signed-request))
      (.build builder))))

(defn- make-link-presigner
  "A request's own presigner, the SDK's way to have S3Presigner's request
   signed at another moment than now: S3Presigner takes no clock."
  ^Signer [settings ^Instant signed-at ^Duration lifetime]
  (let [credentials-provider (make-credentials-provider settings)]
    (reify
      Signer
      (sign [_ _ _]
        (throw (UnsupportedOperationException. "a link's presigner only presigns")))
      Presigner
      (presign [_ request _]
        (sign-in-query credentials-provider settings request signed-at lifetime)))))

(defn presign-get
  "A link that reads the object at the key: S3Presigner's GET, signed at
   signed-at for lifetime-seconds, with response-content-disposition when
   it isn't nil."
  [^S3Presigner presigner settings object-key response-content-disposition ^Instant signed-at lifetime-seconds]
  (let [lifetime (Duration/ofSeconds lifetime-seconds)
        override (AwsRequestOverrideConfiguration/builder)
        request  (GetObjectRequest/builder)
        presign  (GetObjectPresignRequest/builder)]
    (.signer override (make-link-presigner settings signed-at lifetime))
    (.bucket request ^String (:bucket settings))
    (.key request ^String object-key)
    (.responseContentDisposition request ^String response-content-disposition)
    (.overrideConfiguration request ^AwsRequestOverrideConfiguration (.build override))
    (.signatureDuration presign lifetime)
    (.getObjectRequest presign ^GetObjectRequest (.build request))
    (str (.url (.presignGetObject presigner ^GetObjectPresignRequest (.build presign))))))
