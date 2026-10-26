(ns groundwork-file-storage.test-setup
  "What the tests share: where the bucket and Postgres are
   (FILE_STORAGE_TEST_*, see README.md), a store's settings, a pool, the
   log and a meter."
  (:require
   [groundwork-file-storage.api :as file-storage]
   [groundwork-file-storage.bucket :as bucket]
   [groundwork-file-storage.signing :as signing]
   [groundwork-logs.api :as logs]
   [groundwork-metrics.api :as metrics]
   [groundwork-postgres.api :as postgres]
   [groundwork-settings.api :as settings]
   [jsonista.core :as json]))

(def s3-endpoint (System/getenv "FILE_STORAGE_TEST_S3_ENDPOINT"))
(def pg-port (some-> (System/getenv "FILE_STORAGE_TEST_PG_PORT") parse-long))

(def lines (atom []))

(def log
  (logs/log-function (logs/make-logger {:lowest-level :debug :format :json :errors-kept 100
                                        :write-to     #(swap! lines conj (json/read-value %))})))

(defn make-meter []
  (metrics/make-meter {:jvm-metrics false :most-series-per-metric 1000} {:log log}))

(defn make-settings
  "A private store's settings, the test bucket, every key written out."
  []
  {:endpoint             (or s3-endpoint "http://127.0.0.1:1")
   :bucket               "file-storage-test"
   :access-key           (settings/make-secret :groundwork-file-storage/access-key (or (System/getenv "FILE_STORAGE_TEST_S3_ACCESS_KEY") "key"))
   :secret-key           (settings/make-secret :groundwork-file-storage/secret-key (or (System/getenv "FILE_STORAGE_TEST_S3_SECRET_KEY") "secret"))
   :region               "us-east-1"
   :table                :groundwork-file-storage/files
   :public-url           nil
   :cache-purge          nil
   :longest-link-seconds 604800
   :head-bytes           4096
   :deletes?             true
   :connect-timeout-ms   2000
   :request-timeout-ms   10000
   :retries              {:max-retries 2 :first-wait-ms 100 :max-wait-ms 400}})

(defn make-pool []
  (postgres/start-pool! {:host "localhost" :port pg-port :database "postgres" :user (System/getenv "FILE_STORAGE_TEST_PG_USER")
                         :password nil :tls :off :tls-root-cert nil :pool-size 4
                         :connection-wait-ms 30000 :connection-check-wait-ms 5000
                         :connection-max-lifetime-ms 1800000 :connection-keepalive-every-ms 120000
                         :leak-warning-after-ms :none
                         :connect-timeout-seconds 10 :read-timeout-seconds :none :tcp-keepalive? false
                         :statement-timeout-ms 5000 :lock-timeout-ms :none :idle-in-transaction-timeout-ms :none
                         :time-zone "UTC"
                         :metric-buckets {:query-seconds [0.01 0.1 1] :connection-acquire-seconds [0.01 0.1 1]
                                          :connection-usage-seconds [0.01 0.1 1]}}
                        {:log log :meter (make-meter)}))

(defn make-store [file-storage-settings kinds]
  (file-storage/make-file-storage file-storage-settings {:log log :meter (make-meter) :kinds kinds}))

(defn make-bucket-afresh!
  "The store's bucket, made when missing and emptied, through
   groundwork-file-storage's own client."
  [{:keys [client settings]}]
  (bucket/send-to-bucket! client settings {:method "PUT" :path (bucket/write-object-path (:bucket settings) nil) :query []
                                           :headers {} :body nil :payload-hash signing/empty-payload-hash}
                          :bytes)
  (doseq [object-key (bucket/list-object-keys client settings "")]
    (bucket/delete-object! client settings object-key)))
