(ns groundwork-file-storage.settings
  "groundwork-file-storage's settings, and the options its functions take, as malli shapes,
   checked before anything runs. Nothing has a default: every key is
   written out (nil where it is a choice), and a missing or unknown one
   refuses, named, with every other problem at once. Checked by malli
   itself: they are values the app hands in, not data crossing an edge."
  (:require
   [groundwork-file-storage.kinds :as kinds]
   [malli.core :as malli]
   [malli.error :as malli-error]
   [groundwork-settings.api :as settings])
  (:import
   (java.nio.file Files LinkOption Path)))

(def seven-days-seconds
  "The longest a SigV4 presigned link may live: S3's limit, which R2 keeps."
  604800)

(def ^:private address-shape
  [:re {:error/message "should be an address starting with http:// or https://, no / at the end"} #"^https?://.*[^/]$"])

(def table-shape
  "A store's files table: :groundwork-file-storage/<name>, the name
   lower-case words joined by - (groundwork_file_storage.<name> in
   Postgres). Every files table is in groundwork-file-storage's schema, so
   its name alone names it, and its columns' keys (:<name>/key) are no
   other store's."
  [:and
   [:qualified-keyword {:error/message "should be a keyword :groundwork-file-storage/<name>"}]
   [:fn {:error/message "should be :groundwork-file-storage/<name>, the name lower-case words joined by -"}
    #(and (= "groundwork-file-storage" (namespace %))
          (boolean (re-matches #"[a-z][a-z0-9]*(?:-[a-z0-9]+)*" (name %))))]])

(def cache-purge-shape
  "nil for a store without :public-url; for one with it, Cloudflare's
   {:api-url :zone-id :api-token} that purges a deleted file's address,
   or :not-cached when nothing caches its public address."
  [:multi {:dispatch #(cond (nil? %) :none (keyword? %) :not-cached :else :cloudflare)}
   [:none :nil]
   [:not-cached [:= {:error/message "should be :not-cached, nil, or Cloudflare's {:api-url :zone-id :api-token}"} :not-cached]]
   [:cloudflare [:map {:closed true}
                 [:api-url address-shape]
                 [:zone-id [:re {:error/message "should be a Cloudflare zone id, 32 hex digits"} #"^[0-9a-f]{32}$"]]
                 [:api-token settings/secret]]]])

(def settings-shape
  "make-file-storage's settings, every key required.
   :endpoint: the S3 API's address, \"https://<account>.r2.cloudflarestorage.com\"
   or \"https://s3.eu-central-1.amazonaws.com\"; requests go path-style,
   <endpoint>/<bucket>/<key>.
   :bucket: the bucket every file goes into.
   :access-key, :secret-key: a key that may read and write that bucket
   (and read the buckets copy-file! copies from); secrets of
   groundwork-settings's (#secret :groundwork-file-storage/access-key,
   #secret :groundwork-file-storage/secret-key in the app's settings
   file), text written out refused.
   :region: the region requests and links are signed for (R2: \"auto\").
   :table: the store's files table, :groundwork-file-storage/<name>
   (table-shape); one per store.
   :public-url: where browsers read the bucket's files without a link,
   \"https://files.shop.example\" (a public bucket or a CDN in front of
   it), or nil when the bucket is private: make-public-link then throws.
   :cache-purge: what purges a deleted file's public address
   (cache-purge-shape): nil for a private store; Cloudflare's {:api-url
   \"https://api.cloudflare.com/client/v4\" :zone-id … :api-token
   #secret …}, or :not-cached, for a public one.
   :longest-link-seconds: the longest a signed link may read its file, 1
   to 604800 (seven days, S3's longest); a link asked for longer is
   refused.
   :head-bytes: how many of a file's first bytes a kind's :recognize is
   handed, at least 1024 (the built-in kinds read the first kilobyte).
   :deletes?: false for a store that never deletes (one sharing another
   app's bucket): delete-file!, delete-file-after-commit! and
   delete-unnamed-files! then only log and count the refusal.
   :connect-timeout-ms: how long a request waits for the connection.
   :request-timeout-ms: how long a request waits for its answer, after
   the connection.
   :retries: a request the bucket (or Cloudflare) was too busy for (429,
   503, 504), or that never connected, is sent again, at most
   :max-retries more times, waiting :first-wait-ms, then twice as long
   each time, never longer than :max-wait-ms."
  [:map {:closed true}
   [:endpoint [:re {:error/message "should be an address starting with http:// or https://"} #"^https?://[^/]+$"]]
   [:bucket [:string {:min 1}]]
   [:access-key settings/secret]
   [:secret-key settings/secret]
   [:region [:string {:min 1}]]
   [:table table-shape]
   [:public-url [:maybe address-shape]]
   [:cache-purge cache-purge-shape]
   [:longest-link-seconds [:int {:min 1 :max seven-days-seconds}]]
   [:head-bytes [:int {:min 1024}]]
   [:deletes? :boolean]
   [:connect-timeout-ms [:int {:min 1}]]
   [:request-timeout-ms [:int {:min 1}]]
   [:retries [:map {:closed true}
              [:max-retries [:int {:min 0}]]
              [:first-wait-ms [:int {:min 0}]]
              [:max-wait-ms [:int {:min 0}]]]]])

(defn find-settings-rule-errors
  "The problems no one key shows: a :cache-purge that doesn't go with
   :public-url."
  [{:keys [public-url cache-purge] :as file-storage-settings}]
  (when (and (map? file-storage-settings) (contains? file-storage-settings :public-url)
             (contains? file-storage-settings :cache-purge))
    (cond
      (and (nil? public-url) (some? cache-purge))
      {:cache-purge ["should be nil: a store without :public-url has no public address to purge"]}
      (and (some? public-url) (nil? cache-purge))
      {:cache-purge ["should be Cloudflare's {:api-url :zone-id :api-token}, or :not-cached, for a store with a :public-url"]})))

(def kind-shape
  "An app's kind (groundwork-file-storage.kinds), every key required.
   :content-type: \"model/step\", none of the built-in ones.
   :extension: what its files' keys end with, \"step\".
   :recognize: (fn [{:keys [head file-name size open-content]}]
   true-or-false), from the content, never the browser's word.
   :served-as: :inline, or :download (stored with Content-Disposition:
   attachment)."
  [:map {:closed true}
   [:content-type [:re {:error/message "should be a content type, type/subtype, lower-case"}
                   #"^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$"]]
   [:extension [:re {:error/message "should be lower-case letters and digits"} #"^[a-z0-9]+$"]]
   [:recognize fn?]
   [:served-as [:enum :inline :download]]])

(def log-entry
  "The app's log function, (log level event data), made by groundwork-logs."
  [:log [fn? {:error/message "should be the app's log function, made by groundwork-logs"}]])

(def meter-entry
  "The app's meter, made by groundwork-metrics's make-meter: make-file-storage declares
   groundwork-file-storage's metrics on it. A value make-meter didn't make is refused by
   meter (:groundwork-metrics/not-a-meter)."
  [:meter [some? {:error/message "should be the app's meter, made by groundwork-metrics.api/make-meter"}]])

(def options-shape
  "make-file-storage's options: the app's log and meter, and its own
   kinds ([] for none), told after the built-in ones, in this order."
  [:map {:closed true} log-entry meter-entry [:kinds [:sequential kind-shape]]])

(defn find-options-rule-errors
  "The problems no one kind shows: a content type the library tells
   itself, or one declared twice."
  [{:keys [kinds]}]
  (when (sequential? kinds)
    (let [content-types (keep #(when (map? %) (:content-type %)) kinds)
          built-in      (filter kinds/built-in-content-types content-types)
          twice         (for [[content-type n] (frequencies content-types) :when (< 1 n)] content-type)]
      (when (or (seq built-in) (seq twice))
        {:kinds (vec (concat (for [content-type (sort (set built-in))]
                               (str content-type " is a built-in kind: declare none with its content type"))
                             (for [content-type (sort twice)]
                               (str content-type " is declared twice"))))}))))

(def key-prefix-shape
  "The folder a file's key starts with, \"orders/photos\": segments of
   letters, digits, . _ and -, joined by /, no . or .. segment, no / at
   either end."
  [:and
   [:re {:error/message "should be segments of letters, digits, . _ and -, joined by /"}
    #"^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$"]
   [:fn {:error/message "should have no . or .. segment"}
    #(not (re-find #"(^|/)\.\.?(/|$)" %))]])

(defn make-content-types-shape
  "A set of the content types a store knows: the built-in kinds' and its
   app's."
  [known-content-types]
  [:set {:min 1} (into [:enum] (sort known-content-types))])

(defn- is-readable-file? [path]
  (and (string? path)
       (let [p (Path/of ^String path (make-array String 0))]
         (and (Files/isRegularFile p (make-array LinkOption 0)) (Files/isReadable p)))))

(def content-shape
  "store-file!'s content: {:path \"/a/file/on/disk\"}, read from the file
   and streamed to the bucket, or {:bytes byte-array}."
  [:multi {:dispatch #(if (and (map? %) (contains? % :path)) :path :bytes)}
   [:path [:map {:closed true}
           [:path [:fn {:error/message "should be the path of a file that can be read"} is-readable-file?]]]]
   [:bytes [:map {:closed true}
            [:bytes [:fn {:error/message "should be a byte array"} bytes?]]]]])

(defn make-store-options-shape
  "store-file!'s options, every key required.
   :key-prefix: the folder the key starts with (key-prefix-shape).
   :allowed-content-types: what the content may be, a set of the store's
   known-content-types; anything else is refused,
   :groundwork-file-storage/wrong-kind.
   :file-name: the file's name, or nil; handed to the kinds (a .gltf is
   JSON told by its name), never to the bucket."
  [known-content-types]
  [:map {:closed true}
   [:key-prefix key-prefix-shape]
   [:allowed-content-types (make-content-types-shape known-content-types)]
   [:file-name [:maybe :string]]])

(defn- make-upload-settings-shape [content-types-shape]
  [:map {:closed true}
   [:key-prefix key-prefix-shape]
   [:allowed-content-types content-types-shape]
   [:max-file-bytes [:int {:min 1}]]
   [:max-files [:int {:min 1}]]])

(def upload-settings-shape
  "make-upload-handler's settings, every key required.
   :key-prefix: the folder every uploaded file's key starts with.
   :allowed-content-types: what an uploaded file's bytes may be, content
   types the store knows (make-upload-handler checks which).
   :max-file-bytes: the largest file taken; a larger one is refused, the
   others stored. The server's own body limit (bridge's
   :max-body-bytes) must leave room for it.
   :max-files: the most parts one request may carry; more refuse the
   request, 413, nothing stored."
  (make-upload-settings-shape [:set {:min 1} [:string {:min 1}]]))

(defn make-upload-settings-shape-of-store
  "upload-settings-shape, :allowed-content-types the store's known ones."
  [known-content-types]
  (make-upload-settings-shape (make-content-types-shape known-content-types)))

(def upload-options-shape
  "make-upload-handler's options, every key required.
   :pool: the app's pool, the files' rows written on it.
   :shaper: the app's shaper, the answer written as JSON by it.
   :may-upload?: (fn [request] true-or-false), asked before the body is
   read: who may store files is the app's to say."
  [:map {:closed true}
   [:pool some?]
   [:shaper [some? {:error/message "should be the app's shaper, made by groundwork-shaped-json.api/make-shaper-from-settings"}]]
   [:may-upload? fn?]])

(def list-options-shape
  "list-files' options, every key required.
   :key-prefix: only the keys that start with it, \"\" for every key.
   :after-key: only the keys after it, nil to start at the first.
   :limit: at most this many."
  [:map {:closed true}
   [:key-prefix :string]
   [:after-key [:maybe :string]]
   [:limit [:int {:min 1}]]])

(def delete-unnamed-options-shape
  "delete-unnamed-files!' options, every key required.
   :key-prefix: only the keys that start with it, \"\" for every key.
   :page-size: how many rows are read at a time."
  [:map {:closed true}
   [:key-prefix :string]
   [:page-size [:int {:min 1}]]])

(def ^:private expiry-shape
  [:multi {:dispatch #(when (vector? %) (first %))}
   [:from-now [:tuple [:= :from-now] [:int {:min 1}]]]
   [:window [:tuple [:= :window] [:int {:min 1}]]]
   [::malli/default [:fn {:error/message "should be [:from-now seconds] or [:window seconds]"} (constantly false)]]])

(def ^:private download-name-shape
  [:and
   [:string {:min 1 :max 255}]
   [:fn {:error/message "should be a file's name: no control character, no / or \\"}
    #(not (re-find #"[\x00-\x1F\x7F/\\]" %))]])

(def link-options-shape
  "make-signed-link's options, every key required.
   :expiry: how long the link reads its file, at most the store's
   :longest-link-seconds: [:from-now seconds], from now; or [:window
   seconds], signed at the start of the current window and expiring at
   the end of the next (so for up to twice the window), the same address
   for every link made within one window.
   :download-as: nil, or a file name: the answer then says
   Content-Disposition: attachment with that name."
  [:map {:closed true}
   [:expiry expiry-shape]
   [:download-as [:maybe download-name-shape]]])

(defn find-link-rule-errors
  "A link asked to live longer than the store's :longest-link-seconds."
  [longest-link-seconds lifetime-of {:keys [expiry]}]
  (when-let [lifetime (try (lifetime-of expiry) (catch Exception _ nil))]
    (when (> lifetime longest-link-seconds)
      {:expiry [(str "should read for at most the store's :longest-link-seconds, " longest-link-seconds
                     " (this one reads for up to " lifetime ")")]})))

(defn find-errors
  "The data's problems against the shape, humanized; nil when none."
  [shape data]
  (some-> (malli/explain shape data) malli-error/humanize))

(defn throw-errors
  "Throws {:type kind :errors errors} when there are errors; returns nil."
  [what kind errors]
  (when (seq errors)
    (throw (ex-info (str what " have problems: " (pr-str errors))
                    {:type kind :errors errors}))))

(defn- merge-errors [& errors]
  (apply merge-with (fn [a b] (if (and (vector? a) (vector? b)) (into a b) a)) (remove nil? errors)))

(defn check-data-for-shape
  "Returns the data; throws {:type kind :errors …} with every problem at
   once when it doesn't fit the shape, or the rules (a function of the
   data to more errors, or nil) find more."
  [what kind shape rules data]
  (throw-errors what kind (merge-errors (find-errors shape data) (when rules (rules data))))
  data)

(defn check-several
  "Checks several values at once, [shape rules data] each: every
   problem of every one thrown together. Returns nil."
  [what kind & checks]
  (throw-errors what kind (apply merge-errors (for [[shape rules data] checks]
                                                (merge-errors (find-errors shape data) (when rules (rules data)))))))
