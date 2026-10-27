(ns groundwork-file-storage.api
  "Files in an S3 bucket (R2, S3, any S3 API), named by their content and
   recorded in a table. groundwork-file-storage's one public namespace.

     (def files (file-storage/make-file-storage
                 {:endpoint             \"https://<account>.r2.cloudflarestorage.com\"
                  :bucket               \"shop-files\"
                  :access-key           access-key
                  :secret-key           secret-key        ; both groundwork-settings's secrets
                  :region               \"auto\"
                  :table                :groundwork-file-storage/files
                  :public-url           nil               ; a private bucket
                  :cache-purge          nil               ; nothing public to purge
                  :longest-link-seconds 604800
                  :head-bytes           4096
                  :deletes?             true
                  :connect-timeout-ms   10000
                  :request-timeout-ms   60000
                  :retries              {:max-retries 4 :first-wait-ms 500 :max-wait-ms 4000}}
                 {:log log :meter meter :kinds [step-kind]}))

     (file-storage/store-file! files transaction {:path \"/srv/uploads/part-1\"}
                               {:key-prefix \"invoices\" :allowed-content-types #{\"application/pdf\"} :file-name nil})
     ;=> #:file{:key \"invoices/3f1c….pdf\" :sha256 \"3f1c…\" :size 48213
     ;          :content-type \"application/pdf\" :stored-at #inst \"…\"}

     (file-storage/make-signed-link files \"invoices/3f1c….pdf\" {:expiry [:from-now 300] :download-as nil})

   A file's key is its content's: <prefix>/<the sha256's first 32 hex
   digits as a uuid>.<extension of its kind>. The same bytes are the same
   key, stored once; an object is never written over (the put says
   If-None-Match: *). Every file stored is a row of the store's own files
   table (files-table, a map for groundwork-postgres), its full sha256
   with it: a key whose row has another sha256 is refused,
   :groundwork-file-storage/key-taken. The bytes go to the bucket before
   the row is written, so a row always has its object; an object whose
   row was rolled back stays, and the same bytes stored again find it.

   A file's kind is told by its content (groundwork-file-storage.kinds),
   never by what the browser says: the built-in kinds, then the app's
   :kinds; each call says which kinds it takes.

   Content-named, one file may be named by many rows of the app (two
   orders with the same PDF). A row names a file through a reference to
   its store's key column ({:references {:to :files/key :on-delete
   :refuse :on-update :refuse :checked-at :statement}}: a delete of a
   named file is refused); delete-file! deletes a file only when none
   does (Postgres's reference refuses the row's delete, which
   groundwork-file-storage answers :still-named), its row and its object
   together. groundwork-file-storage knows no owners: a file's prefix is
   the app's to choose, and delete-unnamed-files! deletes every file under
   one that no row names.

   groundwork-file-storage writes to the app's log only, :log, and counts
   on the app's meter, :meter (groundwork-file-storage.metrics)."
  (:require
   [groundwork-file-storage.bucket :as bucket]
   [groundwork-file-storage.cache-purge :as cache-purge]
   [groundwork-file-storage.kinds :as kinds]
   [groundwork-file-storage.links :as links]
   [groundwork-file-storage.metrics :as metrics]
   [groundwork-file-storage.settings :as settings]
   [groundwork-file-storage.table :as table]
   [groundwork-file-storage.upload :as upload]
   [groundwork-postgres.api :as postgres]
   [groundwork-logs.api :as logs])
  (:import
   (java.io ByteArrayInputStream InputStream)
   (java.nio.file Files OpenOption Path)
   (java.security MessageDigest)
   (java.time Instant)
   (java.util Arrays HexFormat)))

;; -----------------------------------------------------------------------------
;; Settings and the table

(def settings-shape
  "make-file-storage's settings, every key required. See groundwork-file-storage.settings/settings-shape."
  settings/settings-shape)

(def upload-settings-shape
  "make-upload-handler's settings, every key required. See
   groundwork-file-storage.settings/upload-settings-shape."
  settings/upload-settings-shape)

(defn known-content-types
  "Every content type the store tells by its content, the built-in kinds'
   and its app's: what :allowed-content-types may hold."
  [file-storage]
  (:known-content-types file-storage))

(defn files-table
  "The store's files table, groundwork_file_storage.<its :table's name>, a
   map for groundwork-postgres, to pass with the app's tables: from the
   store, or from its settings (before the store is made). Its columns'
   keys are the table's name and the field (:catalog-files/key), so an
   app's reference names one store's files. A row is never changed;
   delete-file! deletes it with its object, when no row of the app names
   it."
  [file-storage-or-settings]
  (let [table-key (or (get-in file-storage-or-settings [:settings :table]) (:table file-storage-or-settings))]
    (settings/check-data-for-shape "The settings" :groundwork-file-storage/bad-settings
                                   [:map [:table settings/table-shape]] nil {:table table-key})
    (table/make-files-table table-key)))

;; -----------------------------------------------------------------------------
;; The file storage

(defn make-file-storage
  "A file storage from its settings and options ({:log log :meter meter
   :kinds [kind …]}), every key required, checked first
   (:groundwork-file-storage/bad-settings,
   :groundwork-file-storage/bad-options); declares groundwork-file-storage's
   metrics on the meter. :kinds are the app's own kinds
   (groundwork-file-storage.kinds), [] for none; a content type of a
   built-in kind, or one declared twice, is refused. Returns the file
   storage, for every other function; stop it with stop-file-storage!."
  [file-storage-settings options]
  (settings/check-data-for-shape "The settings" :groundwork-file-storage/bad-settings settings/settings-shape
                                 settings/find-settings-rule-errors file-storage-settings)
  (settings/check-data-for-shape "The options" :groundwork-file-storage/bad-options settings/options-shape
                                 settings/find-options-rule-errors options)
  (let [{:keys [log meter]} options
        all-kinds           (into kinds/built-in-kinds (:kinds options))]
    ;; a log groundwork-logs didn't make is refused now, not at the first failure
    (logs/with-context log {})
    (metrics/declare-metrics! meter)
    {:settings            file-storage-settings
     :client              (bucket/make-client file-storage-settings)
     :presigner           (bucket/make-presigner file-storage-settings)
     :http-client         (cache-purge/make-http-client file-storage-settings)
     :kinds               all-kinds
     :known-content-types (set (map :content-type all-kinds))
     :queries             (table/make-queries (:table file-storage-settings))
     :log                 log
     :meter               meter}))

(defn stop-file-storage!
  "Stops the file storage's S3 client, its presigner and its http client."
  [file-storage]
  (bucket/stop-client! (:client file-storage))
  (bucket/stop-client! (:presigner file-storage))
  (cache-purge/stop-http-client! (:http-client file-storage)))

;; -----------------------------------------------------------------------------
;; Storing

(defn- read-head
  "The first at most n bytes of the file."
  ^bytes [^Path path n]
  (with-open [in (Files/newInputStream path (make-array OpenOption 0))]
    (.readNBytes in (int n))))

(defn- describe-content
  "What the kinds are handed ({:head :file-name :size :open-content}),
   and :body, what the bucket is sent: the Path, or the bytes."
  [head-bytes {:keys [path bytes]} file-name]
  (if path
    (let [p (Path/of ^String path (make-array String 0))]
      {:head         (read-head p head-bytes)
       :file-name    file-name
       :size         (Files/size p)
       :open-content #(Files/newInputStream p (make-array OpenOption 0))
       :body         p})
    {:head         (Arrays/copyOf ^bytes bytes (int (min head-bytes (alength ^bytes bytes))))
     :file-name    file-name
     :size         (alength ^bytes bytes)
     :open-content #(ByteArrayInputStream. bytes)
     :body         bytes}))

(defn- hash-content
  "[the content's sha256 as hex, how many bytes it has, its MD5 as hex]:
   a file read once, in pieces, never whole. The sha256 names the key; the
   MD5 goes with the put as Content-MD5, which the bucket checks."
  [{:keys [body open-content]}]
  (let [sha256 (MessageDigest/getInstance "SHA-256")
        md5    (MessageDigest/getInstance "MD5")
        hex    #(.formatHex (HexFormat/of) ^bytes %)]
    (if (bytes? body)
      [(hex (.digest sha256 ^bytes body)) (alength ^bytes body) (hex (.digest md5 ^bytes body))]
      (with-open [^InputStream in (open-content)]
        (let [buffer (byte-array 65536)]
          (loop [total 0]
            (let [n (.read in buffer)]
              (if (neg? n)
                [(hex (.digest sha256)) total (hex (.digest md5))]
                (do (.update sha256 buffer 0 n)
                    (.update md5 buffer 0 n)
                    (recur (+ total n)))))))))))

(defn- make-key
  "<prefix>/<the sha256's first 32 hex digits as a uuid>.<extension>"
  [key-prefix sha256 extension]
  (let [h (subs sha256 0 32)]
    (str key-prefix "/" (subs h 0 8) "-" (subs h 8 12) "-" (subs h 12 16) "-" (subs h 16 20) "-" (subs h 20 32)
         "." extension)))

(defn- find-content-disposition
  "What the object is stored with: attachment for a :download kind."
  [kind]
  (case (:served-as kind)
    :inline   nil
    :download "attachment"))

(defn- remove-added [row] (dissoc row :added))

(defn- check-recorded
  "The row, when it was recorded with this sha256; throws
   :groundwork-file-storage/key-taken otherwise."
  [row object-key sha256]
  (when (not= sha256 (:file/sha256 row))
    (throw (ex-info (str "The key " object-key " is recorded with other bytes")
                    {:type :groundwork-file-storage/key-taken :object-key object-key})))
  row)

(defn- put-and-record!
  "Puts the content unless its key is recorded, then records it; the
   row."
  [{:keys [settings client meter queries]} pool-or-transaction described key-prefix kind]
  (let [[sha256 size md5] (hash-content described)
        object-key    (make-key key-prefix sha256 (:extension kind))
        recorded      (postgres/find-one-row pool-or-transaction (:find-file queries) {:file/key object-key})]
    (if recorded
      (do (metrics/count-stored! meter :already-stored)
          (check-recorded recorded object-key sha256))
      (let [put (try (bucket/put-object-once! client settings object-key (:body described) md5
                                              (:content-type kind) (find-content-disposition kind))
                     (catch Exception e
                       (metrics/count-bucket-failure! meter :store)
                       (throw e)))
            row (postgres/find-one-row pool-or-transaction (:insert-file queries)
                                       {:file/key object-key :file/sha256 sha256 :file/size size
                                        :file/content-type (:content-type kind)})]
        (metrics/count-stored! meter (if (= :put put) :stored :already-stored))
        (remove-added (check-recorded row object-key sha256))))))

(defn- store-if-allowed!
  "The row, or [:wrong-kind the content type found, or nil]."
  [file-storage pool-or-transaction content {:keys [key-prefix allowed-content-types file-name]}]
  (let [described (describe-content (get-in file-storage [:settings :head-bytes]) content file-name)
        kind      (kinds/find-kind (:kinds file-storage) described)]
    (if (contains? allowed-content-types (:content-type kind))
      (put-and-record! file-storage pool-or-transaction described key-prefix kind)
      (do (metrics/count-refused! (:meter file-storage) :wrong-kind)
          [:wrong-kind (:content-type kind)]))))

(defn- find-errors-under
  "The data's problems against the shape, under k when they aren't a map
   of keys."
  [k shape data]
  (when-let [errors (settings/find-errors shape data)]
    (if (map? errors) errors {k errors})))

(defn store-file!
  "Stores the content, named by its content, and records it, on a pool or
   in a transaction (then the row exists only if that commits; the object
   stays either way). The content is {:path \"/a/file\"}, a file on disk:
   its head read for the kind, the whole hashed in pieces, and put from
   the file with its known length, never held whole in memory; or
   {:bytes byte-array}. Options, every key required, checked first with
   the content (:groundwork-file-storage/bad-options): :key-prefix, the
   folder the key starts with (\"invoices\"); :allowed-content-types, the
   kinds the content may be, a set of the store's known-content-types;
   :file-name, the file's name or nil, handed to the kinds. Returns the
   file's row, #:file{:key :sha256 :size :content-type :stored-at}, the
   same for the same bytes. Content of another kind throws
   :groundwork-file-storage/wrong-kind, the bucket's failure
   :groundwork-file-storage/bucket-failed (a file that changed while it
   was stored too: the bucket checks its sha256), a key recorded with
   other bytes :groundwork-file-storage/key-taken."
  [file-storage pool-or-transaction content options]
  (settings/throw-errors "The content and options" :groundwork-file-storage/bad-options
                         (merge (some->> (settings/find-errors settings/content-shape content) (hash-map :content))
                                (find-errors-under :options (settings/make-store-options-shape (known-content-types file-storage))
                                                   options)))
  (let [row (store-if-allowed! file-storage pool-or-transaction content options)]
    (when (vector? row)
      (throw (ex-info (str "The content is none of " (pr-str (sort (:allowed-content-types options))))
                      {:type :groundwork-file-storage/wrong-kind :found (second row)})))
    row))

;; -----------------------------------------------------------------------------
;; Reading

(defn find-file
  "The store's row of the file, or nil when it has no file at that key."
  [file-storage pool-or-transaction object-key]
  (postgres/find-one-row pool-or-transaction (get-in file-storage [:queries :find-file]) {:file/key object-key}))

(defn list-files
  "The store's files' rows, by key. Options, every key required:
   :key-prefix (\"\" for all), :after-key (nil to start at the first; the
   last key of the page before), :limit."
  [file-storage pool-or-transaction options]
  (let [{:keys [key-prefix after-key limit]}
        (settings/check-data-for-shape "The options" :groundwork-file-storage/bad-options settings/list-options-shape nil options)]
    (postgres/find-rows pool-or-transaction (get-in file-storage [:queries :list-files])
                        {:key-prefix key-prefix :after-key after-key :limit limit})))

(defn read-file-bytes
  "The file's bytes from the bucket, or nil when the bucket has no object
   at the key; the bucket's failure throws :groundwork-file-storage/bucket-failed."
  [{:keys [settings client meter]} object-key]
  (try
    (bucket/read-object-bytes client settings object-key)
    (catch Exception e
      (metrics/count-bucket-failure! meter :read)
      (throw e))))

;; -----------------------------------------------------------------------------
;; Copying

(defn copy-file!
  "Copies a file from one store to another, the same key (the same
   bytes): the bucket copies the object itself (S3's CopyObject), the
   bytes never pass through the server, and the target store records its
   row on the pool or in the caller's transaction (then the row exists
   only if that commits; the object stays either way). Both stores' tables
   are in the database of pool-or-transaction; both are on the same
   :endpoint, and the target's key reads the source's bucket. The object
   is stored as the target's kinds serve its content type. Returns the
   target's row, the same when it has the file already. Throws
   :groundwork-file-storage/different-endpoints,
   :groundwork-file-storage/no-such-file (the source has no row at the
   key), :groundwork-file-storage/wrong-kind (the target knows no kind of
   that content type), :groundwork-file-storage/bucket-failed,
   :groundwork-file-storage/key-taken."
  [from-file-storage to-file-storage pool-or-transaction object-key]
  (let [from-endpoint (get-in from-file-storage [:settings :endpoint])
        to-endpoint   (get-in to-file-storage [:settings :endpoint])]
    (when (not= from-endpoint to-endpoint)
      (throw (ex-info (str "copy-file! copies within one endpoint; " from-endpoint " is not " to-endpoint)
                      {:type :groundwork-file-storage/different-endpoints :object-key object-key}))))
  (let [{:keys [settings client meter queries kinds]} to-file-storage
        source (or (find-file from-file-storage pool-or-transaction object-key)
                   (throw (ex-info (str "The store copied from has no file " object-key)
                                   {:type :groundwork-file-storage/no-such-file :object-key object-key})))
        kind   (or (first (filter #(= (:file/content-type source) (:content-type %)) kinds))
                   (throw (ex-info (str "The store copied to knows no kind " (:file/content-type source))
                                   {:type :groundwork-file-storage/wrong-kind :found (:file/content-type source)})))]
    (if-let [recorded (postgres/find-one-row pool-or-transaction (:find-file queries) {:file/key object-key})]
      (do (metrics/count-copied! meter :already-stored)
          (check-recorded recorded object-key (:file/sha256 source)))
      (let [copied (try (bucket/copy-object-once! client settings (get-in from-file-storage [:settings :bucket]) object-key
                                                  (:content-type kind) (find-content-disposition kind))
                        (catch Exception e
                          (metrics/count-bucket-failure! meter :copy)
                          (throw e)))
            row    (postgres/find-one-row pool-or-transaction (:insert-file queries)
                                          {:file/key object-key :file/sha256 (:file/sha256 source) :file/size (:file/size source)
                                           :file/content-type (:file/content-type source)})]
        (metrics/count-copied! meter (if (= :copied copied) :copied :already-stored))
        (remove-added (check-recorded row object-key (:file/sha256 source)))))))

;; -----------------------------------------------------------------------------
;; Deleting

(defn- is-foreign-key-violation?
  "Whether Postgres refused because a row still points at the one deleted."
  [^Throwable e]
  (loop [t e]
    (cond (nil? t) false
          (and (instance? java.sql.SQLException t) (= "23503" (.getSQLState ^java.sql.SQLException t))) true
          :else (recur (.getCause t)))))

(defn- delete-row-and-object!
  "In one transaction: the row deleted (Postgres refuses while a row
   names it), then the object; kept only if both went."
  [{:keys [settings client meter queries]} pool object-key]
  ;; groundwork-file-storage's own, never handed to app code: keeps no work
  (postgres/run-in-transaction! pool {:read-only? false :log nil}
                                (fn [transaction]
                                  (when-let [row (postgres/find-one-row transaction (:delete-file queries) {:file/key object-key})]
                                    (try (bucket/delete-object! client settings object-key)
                                         (catch Exception e
                                           (metrics/count-bucket-failure! meter :delete)
                                           (throw e)))
                                    row))))

(defn- refuse-delete!
  "A store that never deletes: the refusal logged and counted."
  [{:keys [log meter]} what]
  (log :info :groundwork-file-storage/delete-refused (assoc what :groundwork-file-storage/why "the store's :deletes? is false"))
  (metrics/count-deleted! meter :refused)
  :refused)

(defn- purge-from-cache!
  "A deleted public file's address purged from Cloudflare's cache, when
   the store has a :cache-purge map; counted, a failure thrown."
  [{:keys [settings http-client meter]} object-key]
  (when (map? (:cache-purge settings))
    (try
      (cache-purge/purge-address! http-client settings (links/make-public-link settings object-key))
      (metrics/count-purge! meter :purged)
      (catch Exception e
        (metrics/count-purge! meter :failed)
        (throw e)))))

(defn delete-file!
  "Deletes a file: its row and its object, on a pool, in a transaction of
   its own (an object deleted can't come back with a rollback, so a
   transaction handed in is refused, :groundwork-file-storage/needs-a-pool:
   delete after the app's own rows are kept). Only when no row of the app
   names it through a reference to the store's key column: then nothing is
   deleted. Returns :deleted, :still-named, :not-found, or :refused for a
   store that never deletes (:deletes? false: nothing deleted, the refusal
   logged, :info :groundwork-file-storage/delete-refused); counted
   (groundwork_file_storage_files_deleted_total), a deletion written to the
   log (:info :groundwork-file-storage/file-deleted). The object is deleted
   before the row's delete is kept: a file stored again at once puts its
   bytes anew; a failed commit after it leaves a row without its object,
   which read-file-bytes answers nil. The bucket's failure throws
   :groundwork-file-storage/bucket-failed, the row kept. A deleted file of
   a store with Cloudflare's :cache-purge has its public address purged
   after: a purge that fails throws
   :groundwork-file-storage/cache-purge-failed, the file deleted."
  [{:keys [settings log meter] :as file-storage} pool object-key]
  (when (postgres/is-transaction? pool)
    (throw (ex-info (str "delete-file! runs in a transaction of its own: hand it the pool, after the app's transaction is kept"
                         " (delete-file-after-commit! keeps it for then)")
                    {:type :groundwork-file-storage/needs-a-pool :object-key object-key})))
  (if-not (:deletes? settings)
    (refuse-delete! file-storage {:groundwork-file-storage/key object-key})
    (let [outcome (try
                    (if-let [row (delete-row-and-object! file-storage pool object-key)]
                      (do (log :info :groundwork-file-storage/file-deleted {:groundwork-file-storage/key object-key :groundwork-file-storage/size (:file/size row)})
                          :deleted)
                      :not-found)
                    (catch Exception e
                      (if (is-foreign-key-violation? e) :still-named (throw e))))]
      (metrics/count-deleted! meter outcome)
      (when (= :deleted outcome)
        (purge-from-cache! file-storage object-key))
      outcome)))

(defn delete-file-after-commit!
  "Keeps delete-file! on the pool to run once the app's transaction
   commits (groundwork-postgres's run-after-commit!), so a handler deleting the rows
   that named a file deletes the file too, after they are kept; never
   when the transaction rolls back. Returns nil: the outcome comes later,
   counted and logged as delete-file!'s (:still-named when a row still
   names it, :refused by a store that never deletes), and a failure of
   the bucket or the cache purge is written by groundwork-postgres to the
   log the app's transaction carries (groundwork-postgres's :log, a
   message's with its request id), :groundwork-postgres/after-commit-failed.
   Lost if the process dies between the commit and the delete:
   delete-unnamed-files! in a background job finds what is left. Handed a
   pool for the transaction, groundwork-postgres refuses,
   :groundwork-postgres/not-a-transaction.

     (postgres/run-statement! transaction delete-invoice {:invoice/id id})
     (file-storage/delete-file-after-commit! file-storage pool transaction invoice-key)"
  [file-storage pool transaction object-key]
  (postgres/run-after-commit! transaction #(delete-file! file-storage pool object-key)))

(defn delete-unnamed-files!
  "Deletes every file under a prefix that no row of the app names
   (delete-file! each, a page at a time): for a background job after the
   app deletes its rows. Options, every key required: :key-prefix (\"\"
   for all), :page-size. Returns {:deleted n :still-named n}; :refused for
   a store that never deletes (nothing read or deleted, the refusal logged
   and counted once)."
  [{:keys [settings queries] :as file-storage} pool options]
  (let [{:keys [key-prefix page-size]}
        (settings/check-data-for-shape "The options" :groundwork-file-storage/bad-options settings/delete-unnamed-options-shape nil options)]
    (if-not (:deletes? settings)
      (refuse-delete! file-storage {:groundwork-file-storage/key-prefix key-prefix})
      (loop [after-key nil counts {:deleted 0 :still-named 0}]
        (let [rows   (postgres/find-rows pool (:list-files queries) {:key-prefix key-prefix :after-key after-key :limit page-size})
              counts (reduce (fn [counts {:file/keys [key]}]
                               (let [outcome (delete-file! file-storage pool key)]
                                 (if (contains? counts outcome) (update counts outcome inc) counts)))
                             counts rows)]
          (if (< (count rows) page-size)
            counts
            (recur (:file/key (peek rows)) counts)))))))

;; -----------------------------------------------------------------------------
;; Links

(defn make-signed-link
  "A link that reads the file, from a private bucket too (SigV4's
   query-string signature). Options, every key required, checked first
   (:groundwork-file-storage/bad-options): :expiry, [:from-now seconds],
   or [:window seconds] (signed at the start of the current window,
   expiring at the end of the next: every link to the file made within
   one window is the same address, which the browser caches), at most the
   store's :longest-link-seconds; :download-as, nil, or a file name the
   answer gives as Content-Disposition: attachment. Whoever holds it
   reads that file until it expires: hand it only to whom the app lets see
   the file."
  [{:keys [settings presigner]} object-key options]
  (settings/check-data-for-shape "The options" :groundwork-file-storage/bad-options settings/link-options-shape
                                 (partial settings/find-link-rule-errors (:longest-link-seconds settings) links/find-lifetime)
                                 options)
  (links/make-signed-link presigner settings object-key options (Instant/now)))

(defn make-public-link
  "The file's address under the settings' :public-url; throws
   :groundwork-file-storage/no-public-url when it is nil."
  [{:keys [settings]} object-key]
  (links/make-public-link settings object-key))

;; -----------------------------------------------------------------------------
;; Uploads

(defn make-upload-handler
  "A Ring handler for an upload through the server: a multipart POST, one
   part per file (groundwork-file-storage.upload says the answer). The settings
   (upload-settings-shape: :key-prefix :allowed-content-types, content
   types the store knows, :max-file-bytes :max-files) and the options
   (:pool, :shaper, the app's, and :may-upload?, (fn [request]
   true-or-false), asked before the body is read), every key required, are
   checked first. The app puts it at its own path, behind its own
   checks."
  [file-storage upload-settings options]
  (settings/check-data-for-shape "The settings" :groundwork-file-storage/bad-settings
                                 (settings/make-upload-settings-shape-of-store (known-content-types file-storage)) nil upload-settings)
  (settings/check-data-for-shape "The options" :groundwork-file-storage/bad-options settings/upload-options-shape nil options)
  (let [{:keys [pool shaper may-upload?]} options
        {:keys [key-prefix allowed-content-types]} upload-settings]
    (upload/make-upload-handler
     (assoc upload-settings
            :shaper              shaper
            :may-upload?         may-upload?
            :log                 (:log file-storage)
            :count-refused!      #(metrics/count-refused! (:meter file-storage) %)
            ;; groundwork-file-storage's own, never handed to app code: keeps no work
            :run-in-transaction! #(postgres/run-in-transaction! pool {:read-only? false :log nil} %)
            :store-part!         (fn [transaction {:keys [file-name bytes]}]
                                   (let [row (store-if-allowed! file-storage transaction {:bytes bytes}
                                                                {:key-prefix            key-prefix
                                                                 :allowed-content-types allowed-content-types
                                                                 :file-name             file-name})]
                                     (if (vector? row) :wrong-kind row)))))))
