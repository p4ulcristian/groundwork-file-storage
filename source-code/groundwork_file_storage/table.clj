(ns groundwork-file-storage.table
  "A store's files table and its queries, made from the store's :table:
   one table per store, in groundwork-file-storage's own schema, so two
   stores in one database never see each other's rows.

     :groundwork-file-storage/catalog-files
       -> groundwork_file_storage.catalog_files, columns catalog_files__key,
          catalog_files__sha256, catalog_files__size,
          catalog_files__content_type, catalog_files__stored_at

   A column's key is the table's name and the field
   (:catalog-files/key): an app's row names a store's file through a
   reference to that store's key column ({:references {:to
   :catalog-files/key …}}), so a catalog row names only catalog files.
   Every query answers a row as #:file{:key :sha256 :size :content-type
   :stored-at}, whichever store's it is."
  (:require
   [clojure.string :as str]
   [groundwork-postgres.api :as postgres]))

(def ^:private fields [:key :sha256 :size :content-type :stored-at])

(defn- turn-field-into-column-key [table-key field]
  (keyword (name table-key) (name field)))

(defn make-files-table
  "The store's files table, a map for groundwork-postgres."
  [table-key]
  (let [k (partial turn-field-into-column-key table-key)]
    {:table      table-key
     :columns    [[(k :key)          :string       {:primary-key? true}]
                  [(k :sha256)       :string]
                  [(k :size)         :int]
                  [(k :content-type) :string]
                  [(k :stored-at)    :time/instant {:default :now}]]
     :indexes    []
     :uniques    []
     :checks     []
     :references []
     :triggers   []
     :gates      nil
     :on-change  nil}))

(defn make-queries
  "The store's queries: :insert-file, :find-file, :list-files,
   :delete-file. Their params are :file/key, and the list's :key-prefix
   :after-key :limit."
  [table-key]
  (let [table       (postgres/turn-table-key-into-table-name table-key)
        column      #(postgres/turn-key-into-column-name (turn-field-into-column-key table-key %))
        columns     (str/join ", " (map column fields))
        as-file     (str/join ", " (map #(str (column %) " AS " (postgres/turn-key-into-column-name (keyword "file" (name %)))) fields))
        key-column  (column :key)
        make-query  (fn [query-name sql] (postgres/make-query-from-sql-text (str table "/" query-name) sql))]
    {;; a file the bucket holds, recorded once: a key recorded before is left as it is (a file
     ;; never changes); the record as it stands, added saying whether this call wrote it
     :insert-file (make-query "insert_file"
                              (str "WITH added AS (\n"
                                   "  INSERT INTO " table " (" (column :key) ", " (column :sha256) ", " (column :size) ", " (column :content-type) ")\n"
                                   "  VALUES (param('file/key'), param('file/sha256'), param('file/size'), param('file/content-type'))\n"
                                   "  ON CONFLICT (" key-column ") DO NOTHING\n"
                                   "  RETURNING " columns "\n"
                                   ")\n"
                                   "SELECT " as-file ", true AS added FROM added\n"
                                   "UNION ALL\n"
                                   "SELECT " as-file ", false AS added FROM " table "\n"
                                   "WHERE " key-column " = param('file/key') AND NOT EXISTS (SELECT 1 FROM added)"))
     :find-file   (make-query "find_file"
                              (str "SELECT " as-file " FROM " table " WHERE " key-column " = param('file/key')"))
     ;; the files whose keys start with the prefix, after a key (nil: from the first), by key
     :list-files  (make-query "list_files"
                              (str "SELECT " as-file " FROM " table "\n"
                                   "WHERE starts_with(" key-column ", param('key-prefix'))\n"
                                   "  AND (CAST(param('after-key') AS text) IS NULL OR " key-column " > CAST(param('after-key') AS text))\n"
                                   "ORDER BY " key-column "\n"
                                   "LIMIT param('limit')"))
     ;; refused by Postgres while a row of the app names the file
     :delete-file (make-query "delete_file"
                              (str "DELETE FROM " table " WHERE " key-column " = param('file/key')\n"
                                   "RETURNING " as-file))}))
