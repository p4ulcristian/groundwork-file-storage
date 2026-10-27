(ns groundwork-file-storage.api-test
  "The kinds, the settings, the tables and the links' shape run everywhere.
   The rest runs against a real S3 API and a real Postgres when
   FILE_STORAGE_TEST_S3_ENDPOINT and FILE_STORAGE_TEST_PG_PORT are set (see
   README.md); says it was skipped otherwise. Cloudflare's purge is
   answered by a stand-in server in the test, which records each request."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-file-storage.api :as file-storage]
   [groundwork-file-storage.bucket :as bucket]
   [groundwork-file-storage.kinds :as kinds]
   [groundwork-file-storage.links :as links]
   [groundwork-file-storage.test-setup :as setup :refer [log lines make-settings make-store s3-endpoint pg-port]]
   [groundwork-metrics.api :as metrics]
   [groundwork-postgres.api :as postgres]
   [groundwork-settings.api :as settings]
   [groundwork-shaped-json.api :as shaped-json]
   [jsonista.core :as json])
  (:import
   (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
   (java.io ByteArrayInputStream ByteArrayOutputStream File RandomAccessFile)
   (java.net InetSocketAddress URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
   (java.nio.charset StandardCharsets)
   (java.nio.file Files)
   (java.security MessageDigest)
   (java.util.concurrent TimeUnit)))

(def ^:private shaper
  (shaped-json/make-shaper-from-settings
   {:most-shapes-kept 1000
    :json-limits      {:max-nesting-depth 1000 :max-number-length 1000 :max-string-length 20000000
                       :max-name-length 50000 :max-document-length nil :max-token-count nil
                       :max-writing-nesting-depth 1000}}))

(def ^:private png
  (byte-array (concat [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A] (map byte (range 64)))))

(def ^:private pdf
  (.getBytes "%PDF-1.7\n1 0 obj << >> endobj\n%%EOF\n" StandardCharsets/UTF_8))

(def ^:private html
  (.getBytes "<!doctype html><script>alert(1)</script>" StandardCharsets/UTF_8))

(def ^:private step
  (.getBytes "ISO-10303-21;\nHEADER;\nENDSEC;\nEND-ISO-10303-21;\n" StandardCharsets/UTF_8))

(def ^:private step-kind
  "An app's kind: STEP, told by its first line, saved rather than shown."
  {:content-type "model/step"
   :extension    "step"
   :recognize    (fn [{:keys [head]}] (str/starts-with? (String. ^bytes head StandardCharsets/US_ASCII) "ISO-10303-21;"))
   :served-as    :download})

(def ^:private public-zone-id "0123456789abcdef0123456789abcdef")

(defn- make-public-settings
  "A public store: its own bucket and table, its address behind
   Cloudflare (the stand-in at cloudflare-url)."
  [cloudflare-url]
  (assoc (make-settings)
         :bucket      "file-storage-test-public"
         :table       :groundwork-file-storage/public-files
         :public-url  "https://files.shop.example"
         :cache-purge {:api-url   cloudflare-url
                       :zone-id   public-zone-id
                       :api-token (settings/make-secret :groundwork-file-storage/cloudflare-token "cloudflare-test-token")}))

(defn- sha256-of [^bytes bs]
  (-> (MessageDigest/getInstance "SHA-256") (.digest bs) (->> (map #(format "%02x" %)) (apply str))))

(defn- find-problems [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

;; -----------------------------------------------------------------------------
;; Everywhere

(deftest kinds-are-told-by-the-content
  (let [find-type (fn [bs file-name]
                    (:content-type (kinds/find-kind (conj kinds/built-in-kinds step-kind)
                                                    {:head bs :file-name file-name :size (alength ^bytes bs)
                                                     :open-content #(ByteArrayInputStream. bs)})))]
    (is (= "image/png" (find-type png "anything.txt")))
    (is (= "application/pdf" (find-type pdf nil)))
    (is (= "image/jpeg" (find-type (byte-array (map unchecked-byte [0xFF 0xD8 0xFF 0xE0 0 0])) nil)))
    (is (nil? (find-type html "page.png")) "markup is no built-in kind, whatever its name says")
    (is (nil? (find-type (.getBytes "<svg xmlns='http://www.w3.org/2000/svg'/>") "icon.svg")))
    (is (= "model/gltf+json" (find-type (.getBytes "{\"asset\":{}}") "door.gltf")))
    (is (nil? (find-type (.getBytes "{\"asset\":{}}") "door.json")) "JSON is a .gltf only by its name")
    (is (nil? (find-type (byte-array 0) nil)))
    (is (= "model/step" (find-type step "part.stp")) "an app's kind, after the built-in ones")))

(deftest a-retry-wait-of-0-sends-again-at-once
  (doseq [retries [{:max-retries 0 :first-wait-ms 0 :max-wait-ms 0}
                   {:max-retries 2 :first-wait-ms 0 :max-wait-ms 400}
                   {:max-retries 2 :first-wait-ms 100 :max-wait-ms 0}]]
    (let [store (make-store (assoc (make-settings) :retries retries) [])]
      (is (some? (:client store)) (str "the shape takes it, and so does the SDK: " (pr-str retries)))
      (file-storage/stop-file-storage! store))))

(deftest settings-are-every-key-written-out
  (is (= {:region ["missing required key"] :retries ["missing required key"] :deletes? ["missing required key"]}
         (:errors (find-problems #(make-store (dissoc (make-settings) :region :retries :deletes?) [])))))
  (testing "the bucket's key and Cloudflare's token are secrets: written out as text, each is refused, and the text is never said"
    (let [refused (find-problems #(make-store (assoc (make-public-settings "https://api.cloudflare.example")
                                                     :access-key "written-out-access" :secret-key "written-out-secret"
                                                     :cache-purge {:api-url "https://api.cloudflare.example" :zone-id public-zone-id
                                                                   :api-token "written-out-token"})
                                              []))]
      (is (= {:access-key ["should be a #secret, read from the settings' secrets source"]
              :secret-key ["should be a #secret, read from the settings' secrets source"]
              :cache-purge {:api-token ["should be a #secret, read from the settings' secrets source"]}}
             (:errors refused)))
      (is (not (re-find #"written-out" (pr-str refused))))))
  (testing "a cache purge goes with a public address, and only with one"
    (is (= {:cache-purge ["should be nil: a store without :public-url has no public address to purge"]}
           (:errors (find-problems #(make-store (assoc (make-settings) :cache-purge :not-cached) [])))))
    (is (= {:cache-purge ["should be Cloudflare's {:api-url :zone-id :api-token}, or :not-cached, for a store with a :public-url"]}
           (:errors (find-problems #(make-store (assoc (make-settings) :public-url "https://files.shop.example") []))))))
  (testing "a files table is groundwork-file-storage's: in its schema, named by lower-case words"
    (is (= {:table ["should be :groundwork-file-storage/<name>, the name lower-case words joined by -"]}
           (:errors (find-problems #(make-store (assoc (make-settings) :table :app/files) [])))))
    (is (= {:table ["should be :groundwork-file-storage/<name>, the name lower-case words joined by -"]}
           (:errors (find-problems #(file-storage/files-table (assoc (make-settings) :table :groundwork-file-storage/Files)))))))
  (testing "the app's kinds: every key, none with a built-in content type, none twice"
    (is (= {:kinds ["image/png is a built-in kind: declare none with its content type" "model/step is declared twice"]}
           (:errors (find-problems #(make-store (make-settings) [step-kind step-kind (assoc step-kind :content-type "image/png")])))))
    (is (= {:kinds [{:served-as ["should be either :inline or :download"]}]}
           (:errors (find-problems #(make-store (make-settings) [(assoc step-kind :served-as :attachment)]))))))
  (testing "store-file!'s content and options, every problem at once, kinds the store knows"
    (let [store (make-store (make-settings) [])]
      (is (= {:content {:path ["should be the path of a file that can be read"]}
              :key-prefix ["should have no . or .. segment"]
              :allowed-content-types #{["should be either \"application/pdf\", \"image/avif\", \"image/gif\", \"image/jpeg\", \"image/png\", \"image/vnd.radiance\", \"image/webp\", \"model/gltf+json\" or \"model/gltf-binary\""]}}
             (:errors (find-problems #(file-storage/store-file! store nil {:path "/no/such/file"}
                                                                {:key-prefix "a/../b" :allowed-content-types #{"model/step"} :file-name nil})))))
      (is (= #{"model/step" "image/png"} (set (filter #{"model/step" "image/png"}
                                                      (file-storage/known-content-types (make-store (make-settings) [step-kind]))))))
      (file-storage/stop-file-storage! store))))

(deftest one-files-table-per-store
  (let [private (file-storage/files-table (make-settings))
        catalog (file-storage/files-table (make-store (assoc (make-settings) :table :groundwork-file-storage/catalog-files) []))]
    (is (= :groundwork-file-storage/files (:table private)))
    (is (= [:files/key :files/sha256 :files/size :files/content-type :files/stored-at] (map first (:columns private))))
    (is (= :groundwork-file-storage/catalog-files (:table catalog)))
    (is (= [:catalog-files/key :catalog-files/sha256 :catalog-files/size :catalog-files/content-type :catalog-files/stored-at]
           (map first (:columns catalog)))
        "an app's row names one store's files: a reference to :catalog-files/key")))

(deftest links-say-the-bucket-the-key-the-expiry-and-the-name
  (let [a        (make-store (assoc (make-settings) :public-url "https://files.shop.example" :cache-purge :not-cached
                                    :longest-link-seconds 86400)
                             [])
        link     (file-storage/make-signed-link a "orders/a b.png" {:expiry [:from-now 300] :download-as nil})
        named    (file-storage/make-signed-link a "orders/a b.png" {:expiry [:from-now 300] :download-as "Ár lista \"2026\".pdf"})
        windowed (file-storage/make-signed-link a "orders/a b.png" {:expiry [:window 3600] :download-as nil})
        signed   (second (re-find #"X-Amz-Date=(\d{8}T\d{6}Z)" windowed))]
    (is (str/includes? link "/file-storage-test/orders/a%20b.png?"))
    (is (str/includes? link "X-Amz-Expires=300"))
    (is (str/includes? link (str "X-Amz-Credential=" (or (System/getenv "FILE_STORAGE_TEST_S3_ACCESS_KEY") "key") "%2F"))
        "the access key, revealed, names the key that signed")
    (is (re-find #"X-Amz-Signature=[0-9a-f]{64}$" link))
    (testing "a download name rides in the signed query, RFC 6266"
      (is (str/includes? named (str "response-content-disposition="
                                    "attachment%3B%20filename%3D%22_r%20lista%20_2026_.pdf%22%3B%20filename%2A%3DUTF-8%27%27"
                                    "%25C3%2581r%2520lista%2520%25222026%2522.pdf"))))
    (testing "a window: signed at its start, read until the end of the next, the same address all through it"
      (is (str/includes? windowed "X-Amz-Expires=7200"))
      (is (re-find #"T\d\d0000Z$" signed) "an hour's window starts on the hour")
      (is (= windowed (file-storage/make-signed-link a "orders/a b.png" {:expiry [:window 3600] :download-as nil})))
      (let [at #(links/make-signed-link (:presigner a) (:settings a) "orders/a b.png" {:expiry [:window 3600] :download-as nil}
                                        (java.time.Instant/parse %))]
        (is (= (at "2026-10-27T10:00:00Z") (at "2026-10-27T10:17:43Z") (at "2026-10-27T10:59:59Z"))
            "the same address at the window's first second, within it, and at its last")
        (is (str/includes? (at "2026-10-27T10:17:43Z") "X-Amz-Date=20261027T100000Z"))
        (is (not= (at "2026-10-27T10:59:59Z") (at "2026-10-27T11:00:00Z")) "the next window, another")))
    (testing "every key required; no longer than the store allows; a name without a control character or a /"
      (is (= {:expiry ["should read for at most the store's :longest-link-seconds, 86400 (this one reads for up to 172800)"]}
             (:errors (find-problems #(file-storage/make-signed-link a "x.png" {:expiry [:window 86400] :download-as nil})))))
      (is (= {:download-as ["missing required key"]}
             (:errors (find-problems #(file-storage/make-signed-link a "x.png" {:expiry [:from-now 60]})))))
      (is (= {:expiry ["should be [:from-now seconds] or [:window seconds]"]
              :download-as ["should be a file's name: no control character, no / or \\"]}
             (:errors (find-problems #(file-storage/make-signed-link a "x.png" {:expiry [:forever 1] :download-as "a\r\nb.pdf"}))))))
    (is (= "https://files.shop.example/orders/a%20b.png" (file-storage/make-public-link a "orders/a b.png")))
    (is (= :groundwork-file-storage/no-public-url
           (:type (find-problems #(file-storage/make-public-link (assoc-in a [:settings :public-url] nil) "x.png")))))
    (file-storage/stop-file-storage! a)))

;; -----------------------------------------------------------------------------
;; Against S3 and Postgres

(def ^:private orders
  "An app's table whose rows name a private file, through a reference to :files/key."
  {:table      :orders
   :columns    [[:order/id :int {:primary-key? true}]
                [:order/invoice-key :string {:references {:to :files/key :on-delete :refuse :on-update :refuse
                                                          :checked-at :statement}}]]
   :indexes    [{:columns [[:order/invoice-key :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}]
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :gates      nil
   :on-change  nil})

(defn- run-sql! [pool text] (postgres/run-statement! pool (postgres/make-query-from-sql-text "test" text) {}))

(defn- make-tables-afresh! [pool tables]
  (run-sql! pool "DROP SCHEMA IF EXISTS groundwork_file_storage CASCADE; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE; DROP TABLE IF EXISTS public.orders")
  @(:slow-part (postgres/migrate! pool {:tables tables :extensions [] :functions [] :views []} []
                                  {:instant-part {:statement-timeout-ms 5000 :lock-timeout-ms 5000}
                                   :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}
                                   :log          (fn [_ _ _])})))

(defn- fetch [link]
  (let [answer (.send (HttpClient/newHttpClient) (.build (HttpRequest/newBuilder (URI. link)))
                      (HttpResponse$BodyHandlers/ofByteArray))]
    {:status              (.statusCode answer)
     :content-disposition (.orElse (.firstValue (.headers answer) "content-disposition") nil)
     :body                (.body answer)}))

(defn- read-counter [a metric-name]
  (->> (str/split-lines (metrics/write-metrics-text (:meter a)))
       (filter #(str/starts-with? % metric-name))
       (map #(let [[series value] (str/split % #" ")] [series (parse-double value)]))
       (into {})))

(defn- make-multipart-request
  "A POST with these parts: [field file-name-or-nil bytes-or-text]."
  [parts]
  (let [boundary "file-storage-test-boundary"
        out      (ByteArrayOutputStream.)
        write    #(.write out (.getBytes ^String % StandardCharsets/UTF_8))]
    (doseq [[field file-name content] parts]
      (write (str "--" boundary "\r\n"))
      (if file-name
        (write (str "Content-Disposition: form-data; name=\"" field "\"; filename=\"" file-name "\"\r\n"
                    "Content-Type: application/octet-stream\r\n\r\n"))
        (write (str "Content-Disposition: form-data; name=\"" field "\"\r\n\r\n")))
      (if (string? content) (write content) (.write out ^bytes content))
      (write "\r\n"))
    (write (str "--" boundary "--\r\n"))
    (let [body (.toByteArray out)]
      {:request-method :post
       :uri            "/files"
       :headers        {"content-type"   (str "multipart/form-data; boundary=" boundary)
                        "content-length" (str (alength body))}
       :body           (ByteArrayInputStream. body)})))

(defn- read-answer [{:keys [status body]}]
  [status (json/read-value body)])

(defn- make-temporary-file
  "A file under the JVM's temporary folder (java.io.tmpdir), deleted on exit."
  ^File [^bytes bs]
  (let [f (File/createTempFile "file-storage-test" ".bin")]
    (.deleteOnExit f)
    (Files/write (.toPath f) bs (make-array java.nio.file.OpenOption 0))
    f))

(deftest files-in-a-real-bucket
  (if-not (and s3-endpoint pg-port)
    (println "groundwork-file-storage.api-test: the bucket tests skipped, FILE_STORAGE_TEST_S3_ENDPOINT or FILE_STORAGE_TEST_PG_PORT is not set")
    (let [pool (setup/make-pool)
          a    (make-store (make-settings) [step-kind])]
      (try
        (make-tables-afresh! pool [(file-storage/files-table a)])
        (setup/make-bucket-afresh! a)

        (testing "a file is named by its content and recorded"
          (let [row (file-storage/store-file! a pool {:bytes png} {:key-prefix "orders/photos" :allowed-content-types #{"image/png"} :file-name nil})
                sha (sha256-of png)]
            (is (= (str "orders/photos/" (subs sha 0 8) "-" (subs sha 8 12) "-" (subs sha 12 16) "-" (subs sha 16 20) "-"
                        (subs sha 20 32) ".png")
                   (:file/key row)))
            (is (= {:file/sha256 sha :file/size (alength png) :file/content-type "image/png"}
                   (select-keys row [:file/sha256 :file/size :file/content-type])))
            (is (some? (:file/stored-at row)))
            (is (= (seq png) (seq (file-storage/read-file-bytes a (:file/key row)))) "the bucket holds the bytes")
            (is (= row (file-storage/find-file a pool (:file/key row))))))

        (testing "the same bytes are the same key, stored once, from bytes or from a file"
          (let [first-row  (file-storage/store-file! a pool {:bytes png} {:key-prefix "orders/photos" :allowed-content-types #{"image/png"} :file-name "again.png"})
                second-row (file-storage/store-file! a pool {:path (str (make-temporary-file png))}
                                                     {:key-prefix "orders/photos" :allowed-content-types #{"image/png"} :file-name nil})]
            (is (= first-row second-row))
            (is (= 1 (count (file-storage/list-files a pool {:key-prefix "orders/" :after-key nil :limit 10}))))
            (is (= {"groundwork_file_storage_files_stored_total{outcome=\"stored\"}"         1.0
                    "groundwork_file_storage_files_stored_total{outcome=\"already-stored\"}" 2.0}
                   (read-counter a "groundwork_file_storage_files_stored_total")))))

        (testing "a file on disk is stored from the file: its bytes, its size, its key"
          (let [bs  (byte-array (concat (seq pdf) (repeat 300000 (byte 65))))
                row (file-storage/store-file! a pool {:path (str (make-temporary-file bs))}
                                              {:key-prefix "invoices/large" :allowed-content-types #{"application/pdf"} :file-name nil})]
            (is (= {:file/sha256 (sha256-of bs) :file/size (alength bs)} (select-keys row [:file/sha256 :file/size])))
            (is (= (seq bs) (seq (file-storage/read-file-bytes a (:file/key row)))))))

        (testing "the bucket checks the Content-MD5 of the bytes hashed: other bytes are refused, nothing stored"
          (let [md5-of-png (.formatHex (java.util.HexFormat/of) (.digest (MessageDigest/getInstance "MD5") ^bytes png))
                problem    (find-problems #(bucket/put-object-once! (:client a) (:settings a) "changed/x.pdf" pdf md5-of-png
                                                                    "application/pdf" nil))]
            (is (= [:groundwork-file-storage/bucket-failed 400 "BadDigest"] ((juxt :type :status :code) problem)))
            (is (= [] (bucket/list-object-keys (:client a) (:settings a) "changed/")))))

        (testing "an app's :download kind: stored with Content-Disposition: attachment, the browser's word never asked"
          (let [row  (file-storage/store-file! a pool {:bytes step} {:key-prefix "parts" :allowed-content-types #{"model/step"} :file-name "part.pdf"})
                read (fetch (file-storage/make-signed-link a (:file/key row) {:expiry [:from-now 60] :download-as nil}))]
            (is (str/ends-with? (:file/key row) ".step"))
            (is (= "model/step" (:file/content-type row)))
            (is (= [200 "attachment"] [(:status read) (:content-disposition read)]))
            (testing "a link's download name over it"
              (is (= "attachment; filename=\"bracket.step\"; filename*=UTF-8''bracket.step"
                     (:content-disposition (fetch (file-storage/make-signed-link a (:file/key row) {:expiry [:from-now 60] :download-as "bracket.step"}))))))))

        (testing "an object whose row was rolled back is found again, not written over"
          (let [key (:file/key (try (postgres/run-in-transaction! pool {:read-only? false :log nil}
                                                                  (fn [transaction]
                                                                    (let [row (file-storage/store-file! a transaction {:bytes pdf} {:key-prefix "invoices" :allowed-content-types #{"application/pdf"} :file-name nil})]
                                                                      (throw (ex-info "the caller's work failed" {:row row})))))
                                    (catch Exception e (:row (ex-data e)))))]
            (is (nil? (file-storage/find-file a pool key)) "the row went with the transaction")
            (is (some? (file-storage/read-file-bytes a key)) "the object stayed")
            (is (= key (:file/key (file-storage/store-file! a pool {:bytes pdf} {:key-prefix "invoices" :allowed-content-types #{"application/pdf"} :file-name nil}))))
            (is (= [key] (mapv :file/key (file-storage/list-files a pool {:key-prefix "invoices/" :after-key nil :limit 1}))))))

        (testing "content of a kind the call doesn't allow is refused, nothing stored"
          (is (= {:type :groundwork-file-storage/wrong-kind :found nil}
                 (select-keys (find-problems #(file-storage/store-file! a pool {:bytes html} {:key-prefix "x" :allowed-content-types #{"image/png"} :file-name "a.png"}))
                              [:type :found])))
          (is (= {:type :groundwork-file-storage/wrong-kind :found "application/pdf"}
                 (select-keys (find-problems #(file-storage/store-file! a pool {:bytes pdf} {:key-prefix "x" :allowed-content-types #{"image/png"} :file-name nil}))
                              [:type :found])))
          (is (= [] (file-storage/list-files a pool {:key-prefix "x/" :after-key nil :limit 10}))))

        (testing "a signed link reads the file, a changed one doesn't, an expired one doesn't"
          (let [key  (:file/key (first (file-storage/list-files a pool {:key-prefix "orders/" :after-key nil :limit 1})))
                link (file-storage/make-signed-link a key {:expiry [:from-now 60] :download-as nil})
                soon (file-storage/make-signed-link a key {:expiry [:from-now 1] :download-as nil})]
            (is (= [200 (seq png) nil] ((juxt :status (comp seq :body) :content-disposition) (fetch link))))
            (is (= 403 (:status (fetch (str/replace link #"X-Amz-Expires=60" "X-Amz-Expires=600")))))
            (is (= 403 (:status (fetch (str/replace link #"/file-storage-test/orders/" "/file-storage-test/invoices/")))))
            (is (= 200 (:status (fetch (file-storage/make-signed-link a key {:expiry [:window 600] :download-as nil}))))
                "a window's link, signed at the window's start, reads")
            (is (= 200 (:status (fetch soon))))
            (Thread/sleep 2500)
            (is (= 403 (:status (fetch soon))) "past its expiry")))

        (testing "an upload stores the files of an allowed kind and size, and says which it refused"
          (let [handler (file-storage/make-upload-handler a {:key-prefix "uploads" :allowed-content-types #{"image/png" "application/pdf"}
                                                             :max-file-bytes 1000 :max-files 4}
                                                          {:pool pool :shaper shaper :may-upload? (constantly true)})
                big     (byte-array (concat (seq pdf) (repeat 2000 (byte 32))))
                [status {:strs [stored rejected]}]
                (read-answer (handler (make-multipart-request [["photo" "door.png" png]
                                                               ["page" "page.png" html]
                                                               ["scan" "scan.pdf" big]
                                                               ["note" nil "hello"]])))]
            (is (= 200 status))
            (is (= [{"field" "photo" "file-name" "door.png" "content-type" "image/png" "size" (alength png)}]
                   (mapv #(select-keys % ["field" "file-name" "content-type" "size"]) stored)))
            (is (str/starts-with? (get (first stored) "key") "uploads/"))
            (is (= #{{"field" "page" "file-name" "page.png" "reason" "wrong-kind"}
                     {"field" "scan" "file-name" "scan.pdf" "reason" "too-large"}
                     {"field" "note" "file-name" nil "reason" "not-a-file"}}
                   (set rejected)))
            (is (= [] (file-storage/list-files a pool {:key-prefix "uploads/scan" :after-key nil :limit 10})))
            (is (= {"groundwork_file_storage_files_refused_total{reason=\"wrong-kind\"}" 3.0
                    "groundwork_file_storage_files_refused_total{reason=\"too-large\"}"  1.0}
                   (read-counter a "groundwork_file_storage_files_refused_total")))

            (testing "a kind the store doesn't know is refused at the start"
              (is (= :groundwork-file-storage/bad-settings
                     (:type (find-problems #(file-storage/make-upload-handler a {:key-prefix "uploads" :allowed-content-types #{"text/csv"}
                                                                                 :max-file-bytes 1000 :max-files 4}
                                                                              {:pool pool :shaper shaper :may-upload? (constantly true)}))))))
            (testing "more parts than :max-files refuse the whole upload"
              (is (= 413 (first (read-answer (handler (make-multipart-request (repeat 5 ["photo" "p.png" png]))))))))
            (testing "a body that isn't multipart is refused"
              (is (= 400 (first (read-answer (handler {:request-method :post :headers {"content-type" "application/json"}
                                                       :body (ByteArrayInputStream. (.getBytes "{}"))})))))))
          (testing "who may upload is asked before the body is read"
            (let [read?   (atom false)
                  handler (file-storage/make-upload-handler a {:key-prefix "uploads" :allowed-content-types #{"image/png"}
                                                               :max-file-bytes 1000 :max-files 4}
                                                            {:pool pool :shaper shaper :may-upload? (constantly false)})
                  request (update (make-multipart-request [["photo" "door.png" png]]) :body
                                  (fn [body] (proxy [java.io.InputStream] []
                                               (read ([] (reset! read? true) (.read ^java.io.InputStream body))
                                                 ([bs off len] (reset! read? true) (.read ^java.io.InputStream body bs off len))))))]
              (is (= [403 {"error" "Not allowed."}] (read-answer (handler request))))
              (is (false? @read?)))))

        (testing "a bucket that can't be reached fails the store, after its retries"
          (let [gone (make-store (assoc (make-settings) :endpoint "http://127.0.0.1:1") [])]
            (is (= :groundwork-file-storage/bucket-failed
                   (:type (find-problems #(file-storage/store-file! gone pool {:bytes (byte-array (concat (seq png) [1 2 3]))}
                                                                    {:key-prefix "gone" :allowed-content-types #{"image/png"} :file-name nil})))))
            (is (= [] (file-storage/list-files a pool {:key-prefix "gone/" :after-key nil :limit 10})) "no row without its object")
            (file-storage/stop-file-storage! gone)))
        (finally
          (file-storage/stop-file-storage! a)
          (postgres/stop-pool! pool))))))

(deftest a-file-on-disk-is-streamed-not-held
  (if-not (and s3-endpoint pg-port)
    (println "groundwork-file-storage.api-test: the streaming test skipped, FILE_STORAGE_TEST_S3_ENDPOINT or FILE_STORAGE_TEST_PG_PORT is not set")
    (let [pool      (setup/make-pool)
          a         (make-store (make-settings) [])
          file-size (* 256 1024 1024)
          f         (File/createTempFile "file-storage-streamed" ".png")]
      (try
        (make-tables-afresh! pool [(file-storage/files-table a)])
        (setup/make-bucket-afresh! a)
        ;; a PNG's signature, then 256 MB of zeros (a sparse file: quick to make)
        (with-open [file (RandomAccessFile. f "rw")]
          (.write file (byte-array (map unchecked-byte [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A])))
          (.setLength file file-size))
        (let [process (-> (ProcessBuilder. ^java.util.List [(str (System/getProperty "java.home") "/bin/java")
                                                            "-Xmx64m"
                                                            (str "-Djava.io.tmpdir=" (System/getProperty "java.io.tmpdir"))
                                                            "-cp" (System/getProperty "java.class.path")
                                                            "clojure.main" "-m" "groundwork-file-storage.streaming-child" (str f)])
                          (.redirectErrorStream true)
                          (.start))
              output  (future (slurp (.getInputStream process)))
              ended?  (.waitFor process 300 TimeUnit/SECONDS)
              said    (some->> (str/split-lines @output) (filter #(str/starts-with? % "{:row")) first edn/read-string)
              sha     (with-open [in (io/input-stream f)]
                        (let [digest (MessageDigest/getInstance "SHA-256") buffer (byte-array 65536)]
                          (loop [] (let [n (.read in buffer)] (when (pos? n) (.update digest buffer 0 n) (recur))))
                          (apply str (map #(format "%02x" %) (.digest digest)))))]
          (is (and ended? (zero? (.exitValue process))) (str "the child JVM, a 64 MB heap, stored a 256 MB file:\n" @output))
          (is (= {:file/sha256 sha :file/size file-size} (select-keys (:row said) [:file/sha256 :file/size])))
          (is (<= (* 4 (:max-heap-bytes said)) file-size) "the child's heap is a quarter of the file at most")
          (is (< (:largest-heap-used-bytes said) (quot file-size 4))
              (str "the heap never held the file: at most " (:largest-heap-used-bytes said) " bytes used"))
          (is (= [(get-in said [:row :file/key])] (bucket/list-object-keys (:client a) (:settings a) "streamed/"))
              "the bucket has the object")
          (println "groundwork-file-storage.api-test: a" file-size "byte file stored by a JVM of at most"
                   (:max-heap-bytes said) "heap bytes; its heap's largest use" (:largest-heap-used-bytes said) "bytes"))
        (finally
          (.delete f)
          (file-storage/stop-file-storage! a)
          (postgres/stop-pool! pool))))))

;; -----------------------------------------------------------------------------
;; Two stores, copying, deleting, the cache purge

(defn- start-cloudflare!
  "A stand-in for Cloudflare's API: records each request, answers what
   (answer) says, [status body]."
  [answer]
  (let [requests (atom [])
        server   (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/" (reify HttpHandler
                                 (handle [_ exchange]
                                   (let [^HttpExchange exchange exchange
                                         [status body] (answer)
                                         bs            (.getBytes ^String body StandardCharsets/UTF_8)]
                                     (swap! requests conj {:method        (.getRequestMethod exchange)
                                                           :path          (.getPath (.getRequestURI exchange))
                                                           :authorization (.getFirst (.getRequestHeaders exchange) "authorization")
                                                           :body          (json/read-value (slurp (.getRequestBody exchange)))})
                                     (.sendResponseHeaders exchange status (alength bs))
                                     (with-open [out (.getResponseBody exchange)] (.write out bs))))))
    (.start server)
    {:server   server
     :url      (str "http://127.0.0.1:" (.getPort (.getAddress server)))
     :requests requests}))

(deftest two-stores-copying-and-deleting
  (if-not (and s3-endpoint pg-port)
    (println "groundwork-file-storage.api-test: the store tests skipped, FILE_STORAGE_TEST_S3_ENDPOINT or FILE_STORAGE_TEST_PG_PORT is not set")
    (let [answer     (atom [200 "{\"success\":true,\"errors\":[],\"messages\":[],\"result\":{\"id\":\"x\"}}"])
          cloudflare (start-cloudflare! #(deref answer))
          pool       (setup/make-pool)
          private    (make-store (make-settings) [step-kind])
          public     (make-store (make-public-settings (:url cloudflare)) [step-kind])
          keeper     (make-store (assoc (make-settings) :deletes? false) [step-kind])
          pdf?       {:key-prefix "invoices" :allowed-content-types #{"application/pdf"} :file-name nil}]
      (try
        (make-tables-afresh! pool [(file-storage/files-table private) (file-storage/files-table public) orders])
        (setup/make-bucket-afresh! private)
        (setup/make-bucket-afresh! public)

        (testing "two stores in one database never see each other's rows"
          (let [row (file-storage/store-file! private pool {:bytes png} {:key-prefix "products" :allowed-content-types #{"image/png"} :file-name nil})]
            (is (some? (file-storage/find-file private pool (:file/key row))))
            (is (nil? (file-storage/find-file public pool (:file/key row))))
            (is (= [] (file-storage/list-files public pool {:key-prefix "" :after-key nil :limit 10})))))

        (testing "a file copied to another store by the bucket: the same key, its row in the caller's transaction"
          (let [part (file-storage/store-file! private pool {:bytes step} {:key-prefix "products" :allowed-content-types #{"model/step"} :file-name nil})
                key  (:file/key part)]
            (is (thrown? clojure.lang.ExceptionInfo
                         (postgres/run-in-transaction! pool {:read-only? false :log nil}
                                                       (fn [transaction]
                                                         (is (= (assoc part :file/stored-at nil)
                                                                (assoc (file-storage/copy-file! private public transaction key) :file/stored-at nil)))
                                                         (throw (ex-info "undo" {}))))))
            (is (nil? (file-storage/find-file public pool key)) "rolled back: no row in the public store")
            (let [copied (file-storage/copy-file! private public pool key)]
              (is (= (dissoc part :file/stored-at) (dissoc copied :file/stored-at)))
              (is (= copied (file-storage/find-file public pool key)))
              (is (= (seq step) (seq (file-storage/read-file-bytes public key))))
              (is (= copied (file-storage/copy-file! private public pool key)) "copied again: the row there already")
              (is (= "attachment" (:content-disposition (fetch (file-storage/make-signed-link public key {:expiry [:from-now 60] :download-as nil}))))
                  "stored as the target's kind serves it"))
            (is (= {"groundwork_file_storage_files_copied_total{outcome=\"copied\"}"         1.0
                    "groundwork_file_storage_files_copied_total{outcome=\"already-stored\"}" 2.0}
                   (read-counter public "groundwork_file_storage_files_copied_total"))
                "the first copy's object stayed after the rollback; the second found it")
            (is (= :groundwork-file-storage/no-such-file
                   (:type (find-problems #(file-storage/copy-file! private public pool "products/none.step")))))
            (is (= :groundwork-file-storage/wrong-kind
                   (:type (find-problems #(file-storage/copy-file! private (make-store (make-public-settings (:url cloudflare)) []) pool key)))))
            (is (= :groundwork-file-storage/different-endpoints
                   (:type (find-problems #(file-storage/copy-file! private (assoc-in public [:settings :endpoint] "https://elsewhere.example") pool key)))))))

        (let [key (:file/key (file-storage/store-file! private pool {:bytes pdf} pdf?))]
          (run-sql! pool (str "INSERT INTO orders VALUES (1, '" key "'), (2, '" key "')"))
          (testing "a file two rows name, one content for two orders, is kept while either names it"
            (is (= :still-named (file-storage/delete-file! private pool key)))
            (run-sql! pool "DELETE FROM orders WHERE order__id = 1")
            (is (= :still-named (file-storage/delete-file! private pool key)))
            (is (some? (file-storage/find-file private pool key)))
            (is (= (seq pdf) (seq (file-storage/read-file-bytes private key))) "the object stays with its row"))
          (testing "named by none: its row and its object go"
            (run-sql! pool "DELETE FROM orders")
            (reset! lines [])
            (is (= :deleted (file-storage/delete-file! private pool key)))
            (is (nil? (file-storage/find-file private pool key)))
            (is (nil? (file-storage/read-file-bytes private key)))
            (is (= [["info" "groundwork-file-storage/file-deleted" key]]
                   (map (juxt #(get % "level") #(get % "event") #(get-in % ["data" "groundwork-file-storage/key"])) @lines)))
            (is (= [] @(:requests cloudflare)) "a private store purges nothing"))
          (testing "a key no file has"
            (is (= :not-found (file-storage/delete-file! private pool key))))
          (testing "the same bytes stored again are put anew"
            (is (= key (:file/key (file-storage/store-file! private pool {:bytes pdf} pdf?))))
            (is (= (seq pdf) (seq (file-storage/read-file-bytes private key)))))
          (testing "a transaction is refused: a deleted object can't come back with a rollback"
            (is (= :groundwork-file-storage/needs-a-pool
                   (:type (find-problems #(postgres/run-in-transaction! pool {:read-only? false :log nil} (fn [t] (file-storage/delete-file! private t key))))))))
          (testing "kept for after the app's transaction: never after a rollback, deleted after a commit"
            (run-sql! pool (str "INSERT INTO orders VALUES (5, '" key "')"))
            (is (thrown? clojure.lang.ExceptionInfo
                         (postgres/run-in-transaction! pool {:read-only? false :log log}
                                                       (fn [transaction]
                                                         (postgres/run-statement! transaction (postgres/make-query-from-sql-text "test" "DELETE FROM orders WHERE order__id = 5") {})
                                                         (is (nil? (file-storage/delete-file-after-commit! private pool transaction key)))
                                                         (throw (ex-info "undo" {}))))))
            (is (some? (file-storage/find-file private pool key)) "rolled back: the row and the file stay")
            (postgres/run-in-transaction! pool {:read-only? false :log log}
                                          (fn [transaction]
                                            (postgres/run-statement! transaction (postgres/make-query-from-sql-text "test" "DELETE FROM orders WHERE order__id = 5") {})
                                            (file-storage/delete-file-after-commit! private pool transaction key)
                                            (is (some? (file-storage/find-file private transaction key)) "not before the commit")))
            (is (nil? (file-storage/find-file private pool key)) "its row and its object gone after the commit")
            (is (nil? (file-storage/read-file-bytes private key)))
            (is (= key (:file/key (file-storage/store-file! private pool {:bytes pdf} pdf?))) "stored again for the tests below"))
          (testing "a store that never deletes: every delete only logged and counted"
            (reset! lines [])
            (is (= :refused (file-storage/delete-file! keeper pool key)))
            (postgres/run-in-transaction! pool {:read-only? false :log log}
                                          #(file-storage/delete-file-after-commit! keeper pool % key))
            (is (= :refused (file-storage/delete-unnamed-files! keeper pool {:key-prefix "invoices/" :page-size 10})))
            (is (some? (file-storage/find-file private pool key)))
            (is (= (seq pdf) (seq (file-storage/read-file-bytes private key))) "the object is there")
            (is (= [["info" "groundwork-file-storage/delete-refused" key nil]
                    ["info" "groundwork-file-storage/delete-refused" key nil]
                    ["info" "groundwork-file-storage/delete-refused" nil "invoices/"]]
                   (map (juxt #(get % "level") #(get % "event") #(get-in % ["data" "groundwork-file-storage/key"])
                              #(get-in % ["data" "groundwork-file-storage/key-prefix"]))
                        @lines)))
            (is (= {"groundwork_file_storage_files_deleted_total{outcome=\"refused\"}" 3.0}
                   (read-counter keeper "groundwork_file_storage_files_deleted_total"))))
          (testing "every file under a prefix that no row names, a page at a time"
            (let [named (:file/key (file-storage/store-file! private pool {:bytes png} {:key-prefix "invoices" :allowed-content-types #{"image/png"} :file-name nil}))]
              (file-storage/store-file! private pool {:bytes (byte-array (concat (seq png) [7]))} {:key-prefix "invoices" :allowed-content-types #{"image/png"} :file-name nil})
              (file-storage/store-file! private pool {:bytes png} {:key-prefix "elsewhere" :allowed-content-types #{"image/png"} :file-name nil})
              (run-sql! pool (str "INSERT INTO orders VALUES (3, '" named "')"))
              (is (= {:deleted 2 :still-named 1} (file-storage/delete-unnamed-files! private pool {:key-prefix "invoices/" :page-size 1})))
              (is (= [named] (map :file/key (file-storage/list-files private pool {:key-prefix "invoices/" :after-key nil :limit 10}))))
              (is (= 1 (count (file-storage/list-files private pool {:key-prefix "elsewhere/" :after-key nil :limit 10}))) "another prefix is left")))
          (testing "counted by outcome"
            (is (= {"groundwork_file_storage_files_deleted_total{outcome=\"deleted\"}"     4.0
                    "groundwork_file_storage_files_deleted_total{outcome=\"still-named\"}" 3.0
                    "groundwork_file_storage_files_deleted_total{outcome=\"not-found\"}"   1.0}
                   (read-counter private "groundwork_file_storage_files_deleted_total")))))

        (testing "a public file deleted: its address purged from Cloudflare's cache after its object"
          (let [key (:file/key (file-storage/store-file! public pool {:bytes pdf} pdf?))]
            (reset! (:requests cloudflare) [])
            (is (= :deleted (file-storage/delete-file! public pool key)))
            (is (= [{:method        "POST"
                     :path          (str "/zones/" public-zone-id "/purge_cache")
                     :authorization "Bearer cloudflare-test-token"
                     :body          {"files" [(str "https://files.shop.example/" key)]}}]
                   @(:requests cloudflare)))
            (testing "a purge Cloudflare refuses throws, the file deleted, counted"
              (reset! answer [400 "{\"success\":false,\"errors\":[{\"code\":1012,\"message\":\"Request must contain one of files\"}]}"])
              (file-storage/store-file! public pool {:bytes pdf} pdf?)
              (let [problem (find-problems #(file-storage/delete-file! public pool key))]
                (is (= {:type :groundwork-file-storage/cache-purge-failed :status 400
                        :errors [{"code" 1012 "message" "Request must contain one of files"}]
                        :address (str "https://files.shop.example/" key)}
                       problem))
                (is (not (re-find #"cloudflare-test-token" (pr-str problem))) "the token is never said"))
              (is (nil? (file-storage/find-file public pool key)))
              (is (nil? (file-storage/read-file-bytes public key)))
              (is (= {"groundwork_file_storage_cache_purges_total{outcome=\"purged\"}" 1.0
                      "groundwork_file_storage_cache_purges_total{outcome=\"failed\"}" 1.0}
                     (read-counter public "groundwork_file_storage_cache_purges_total"))))))
        (finally
          (run-sql! pool "DROP TABLE IF EXISTS public.orders")
          (doseq [store [private public keeper]] (file-storage/stop-file-storage! store))
          (postgres/stop-pool! pool)
          (.stop ^HttpServer (:server cloudflare) 0))))))
