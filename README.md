# groundwork-file-storage

Files for a Clojure app, in an S3 bucket (R2, S3, any S3 API). Each file
is named by its content: the same bytes are the same key, stored once, and
an object is never written over. Every file stored is a row of its store's
own files table (`groundwork_file_storage.files`, or another name per
store), so the app can list what it has. A browser reads a file through a
signed link that expires, or a public address; an upload goes through the
server, each file checked by its bytes before it is stored; a file on disk
is streamed to the bucket, never held whole.

```clojure
io.github.p4ulcristian/groundwork-file-storage {:git/tag "v0.2.3" :git/sha "d20fc3d"}
```

Needs [groundwork-postgres](https://github.com/p4ulcristian/groundwork-postgres) (the files tables),
[groundwork-shaped-json](https://github.com/p4ulcristian/groundwork-shaped-json) (the upload's answer),
[groundwork-logs](https://github.com/p4ulcristian/groundwork-logs) and
[groundwork-metrics](https://github.com/p4ulcristian/groundwork-metrics) (handed in),
[groundwork-settings](https://github.com/p4ulcristian/groundwork-settings) (the bucket's key and
Cloudflare's token are its secrets), ring-core (the multipart parser), jsonista (the cache purge)
and malli. The bucket is reached through the AWS SDK for Java v2 (its S3 client and S3Presigner,
on its Apache 5 http client), set for R2 as R2 documents
([why](decisions/2026-10-27-the-bucket-through-the-aws-sdk-for-java-v2.md)).
One public namespace, `groundwork-file-storage.api`.

## The api

| name | does |
|---|---|
| `make-file-storage` `stop-file-storage!` `settings-shape` | a file storage from its settings and `{:log :meter :kinds}`, every key required, checked first; declares groundwork-file-storage's metrics |
| `files-table` | the store's files table, `groundwork_file_storage.<its :table's name>`, as a map for groundwork-postgres, to migrate with the app's tables; from the store or its settings |
| `store-file!` `known-content-types` | stores `{:path}` (a file on disk, streamed) or `{:bytes}` under their content's key and records them, on a pool or in a transaction; `{:key-prefix :allowed-content-types :file-name}`; the row. The content types the store knows: the built-in kinds' and the app's |
| `find-file` `list-files` | the store's row of a file by key; its rows by prefix, after a key, at most a limit |
| `read-file-bytes` | a file's bytes from the bucket |
| `copy-file!` | a file copied from one store to another by the bucket (CopyObject), the same key, the target's row on a pool or in the caller's transaction |
| `delete-file!` `delete-unnamed-files!` | a file's row and object deleted, on a pool, only when no row of the app names it (a reference to the store's key column): `:deleted`, `:still-named`, `:not-found`, or `:refused` by a store that never deletes; every file under a prefix no row names. A public file's address purged from Cloudflare's cache after |
| `delete-file-after-commit!` | `delete-file!` on the pool kept for once the app's transaction commits (groundwork-postgres's `run-after-commit!`), never after a rollback; nil back, the outcome counted and logged then |
| `make-signed-link` `make-public-link` | a link that reads the file, `{:expiry [:from-now s] or [:window s] :download-as nil-or-a-name}`, at most `:longest-link-seconds`; its address under `:public-url` |
| `make-upload-handler` `upload-settings-shape` | a Ring handler for a multipart upload, one part per file, after the app's `:may-upload?` |

```clojure
(require '[groundwork-file-storage.api :as file-storage])

(def step-kind                                         ; one of the app's own kinds
  {:content-type "model/step"
   :extension    "step"
   :recognize    (fn [{:keys [head file-name size open-content]}]
                   (str/starts-with? (String. ^bytes head "US-ASCII") "ISO-10303-21;"))
   :served-as    :download})                            ; stored with Content-Disposition: attachment

(def files-settings
  {:endpoint             "https://<account>.r2.cloudflarestorage.com"
   :bucket               "shop-files"
   :access-key           access-key   ; read and write on that bucket
   :secret-key           secret-key   ; both groundwork-settings's secrets: #secret :groundwork-file-storage/access-key, :groundwork-file-storage/secret-key
   :region               "auto"       ; what requests and links are signed for
   :table                :groundwork-file-storage/files
   :public-url           nil          ; private: files are read through signed links
   :cache-purge          nil          ; nothing public to purge
   :longest-link-seconds 604800
   :head-bytes           4096         ; what a kind's :recognize sees of a file's start
   :deletes?             true         ; false on a copy of the app that shares the bucket
   :connect-timeout-ms   10000
   :request-timeout-ms   60000
   :retries              {:max-retries 4 :first-wait-ms 500 :max-wait-ms 4000}})

(def files (file-storage/make-file-storage files-settings {:log log :meter meter :kinds [step-kind]}))

(def public-files                                       ; a second store: its own bucket and table
  (file-storage/make-file-storage (assoc files-settings
                                         :bucket      "shop-public"
                                         :table       :groundwork-file-storage/public-files
                                         :public-url  "https://files.shop.example"
                                         :cache-purge {:api-url   "https://api.cloudflare.com/client/v4"
                                                       :zone-id   "<the zone's 32 hex digits>"
                                                       :api-token cloudflare-token})   ; #secret :groundwork-file-storage/cloudflare-token
                                  {:log log :meter meter :kinds [step-kind]}))

(postgres/migrate! pool {:tables (conj app-tables (file-storage/files-table files) (file-storage/files-table public-files))
                         :extensions [] :functions [] :views []}
                   changes migrate-options)

;; an order's invoice, in the transaction that saves the order, from the file on disk
(def invoice (file-storage/store-file! files transaction {:path "/srv/app/uploads/invoice-104.pdf"}
                                       {:key-prefix            "invoices"
                                        :allowed-content-types #{"application/pdf"}
                                        :file-name             nil}))
;=> #:file{:key "invoices/3f1c9a2e-77b0-4d1e-9c4a-0b8e1f2a6c3d.pdf" :sha256 "3f1c9a2e…"
;          :size 48213 :content-type "application/pdf" :stored-at #object[…]}

(file-storage/make-signed-link files (:file/key invoice) {:expiry [:from-now 604800] :download-as "Invoice 104.pdf"})
;=> "https://<account>.r2.cloudflarestorage.com/shop-files/invoices/3f1c….pdf?X-Amz-Algorithm=…&X-Amz-Signature=…"
(file-storage/make-signed-link files photo-key {:expiry [:window 3600] :download-as nil})   ; one address per hour: cached

;; a product published: its photo copied by the bucket, the same key, the row in the transaction
(file-storage/copy-file! files public-files transaction photo-key)

;; uploads of a shop's photos, at the app's own path
(def upload-photos
  (file-storage/make-upload-handler files
                                    {:key-prefix            "shops/photos"
                                     :allowed-content-types #{"image/png" "image/jpeg" "image/webp"}
                                     :max-file-bytes        10000000
                                     :max-files             10}
                                    {:pool        pool
                                     :shaper      shaper
                                     :may-upload? (fn [request] (signed-in? request))}))   ; asked before the body is read
```

```
POST /files/photos   multipart/form-data, one part per file

200 {"stored":   [{"field":"photo","file-name":"door.png","key":"shops/photos/….png",
                   "sha256":"…","size":48213,"content-type":"image/png"}],
     "rejected": [{"field":"notes","file-name":"notes.html","reason":"wrong-kind"}]}
```

A rejected part's reason is `wrong-kind`, `too-large` or `not-a-file` (a
form field); the others are stored. The whole upload is refused, nothing
stored, with 403 (`:may-upload?` said no), 400 (not multipart), 413 (more
parts than `:max-files`) or 503 (the bucket or the database failed,
written to the log as `:groundwork-file-storage/upload-failed`). The server's own body limit
must leave room for an upload's parts together.

The kinds groundwork-file-storage tells by their bytes: PNG, JPEG, GIF, WebP, AVIF,
Radiance HDR, PDF, glTF (binary, and JSON named `.gltf`). Never markup:
SVG and HTML are none of them
([why](decisions/2026-10-23-a-files-kind-is-told-by-its-bytes.md)). The app
adds its own kinds as data, `:kinds`: a content type (none of the built-in
ones), an extension, a `:recognize` function handed the content's head, its
name, its size and a way to read all of it, and `:served-as` `:inline` or
`:download`; the browser's declared type is never used
([why](decisions/2026-10-26-the-apps-own-kinds-are-data.md)).

A key is `<prefix>/<the sha256's first 32 hex digits as a uuid>.<extension>`;
the object is put with `If-None-Match: *` and the `Content-MD5` of the bytes
hashed, which the bucket checks, and the row keeps the full sha256
([why](decisions/2026-10-23-a-file-is-named-by-its-content.md)).
The object is put before its row is written: a row always has its object,
and an object whose row was rolled back is found again by the same bytes
([why](decisions/2026-10-23-every-file-is-a-row-of-attic-files.md)). A file
on disk is hashed in pieces (its sha256 and MD5 in one read) and put from the file with its known length:
a store holds a buffer, whatever the file's size
([why](decisions/2026-10-26-a-file-on-disk-is-streamed-to-the-bucket.md)).

Each store has its own table, `:table` `:groundwork-file-storage/<name>`;
its columns' keys are the table's name (`:catalog-files/key`), so an app's
row names one store's files: `[:product/model-key :string {:references {:to
:catalog-files/key :on-delete :refuse :on-update :refuse :checked-at
:statement}}]`. Every function answers a row as `#:file{…}`
([why](decisions/2026-10-26-one-files-table-per-store.md)).

A signed link's lifetime is the call's, `[:from-now seconds]` or `[:window
seconds]` (signed at the window's start, expiring at the next one's end:
the same address all through a window), capped by the store's
`:longest-link-seconds`; `:download-as` names the file the browser saves
([why](decisions/2026-10-26-a-links-lifetime-and-download-name-are-the-calls.md)).
`copy-file!` has the bucket copy a file between two stores on one endpoint
([why](decisions/2026-10-26-a-file-is-copied-between-stores-by-the-bucket.md)).

`make-file-storage` declares, on the app's meter: `groundwork_file_storage_files_stored_total{outcome}`
(`stored`, `already-stored`), `groundwork_file_storage_files_refused_total{reason}`
(`wrong-kind`, `too-large`), `groundwork_file_storage_files_deleted_total{outcome}`
(`deleted`, `still-named`, `not-found`, `refused`), `groundwork_file_storage_files_copied_total{outcome}`
(`copied`, `already-stored`), `groundwork_file_storage_cache_purges_total{outcome}`
(`purged`, `failed`), `groundwork_file_storage_bucket_failures_total{operation}`
(`store`, `read`, `delete`, `copy`).

A file is deleted, row and object, only when no row of the app names it
through a reference to its store's key column; Postgres refuses the delete
otherwise, and nothing goes. `delete-file!` runs on a pool, after the app's own rows
are kept ([why](decisions/2026-10-23-a-file-is-deleted-only-when-no-row-names-it.md));
`delete-file-after-commit!` keeps it, on the pool, for once the app's
transaction commits (groundwork-postgres's `run-after-commit!`; never after a
rollback), so a handler that deletes the rows naming a file deletes the
file in the same step: `(file-storage/delete-file-after-commit! files pool
transaction key)`, nil back, the outcome counted and logged later
([groundwork-postgres's why](https://github.com/p4ulcristian/groundwork-postgres/blob/tidy-up/decisions/2026-10-23-a-transaction-keeps-work-to-run-after-its-commit.md)).
A deleted file of a store with Cloudflare's `:cache-purge` has its public
address purged from Cloudflare's cache after its object; a purge that fails
throws `:groundwork-file-storage/cache-purge-failed`
([why](decisions/2026-10-26-a-deleted-public-file-is-purged-from-the-cache.md)).
A store with `:deletes? false` deletes nothing: every delete is logged
(`:info :groundwork-file-storage/delete-refused`) and counted (`refused`)
([why](decisions/2026-10-26-a-store-that-never-deletes.md)).

Every setting and option is required: a missing or unknown one is
refused, named, with every other problem at once, before anything runs.
What stays fixed, and why, is in
[the decision](decisions/2026-10-23-every-value-that-sets-behaviour-is-a-setting.md).

## Tests

```sh
clojure -X:test     # the bucket tests are skipped without a bucket and a database

# with SeaweedFS's S3 API and a throwaway Postgres, everything under ~/.cache/file-storage-test:
mkdir -p ~/.cache/file-storage-test/tmp
echo '{"identities":[{"name":"t","credentials":[{"accessKey":"file-storage-test-key","secretKey":"file-storage-test-secret"}],"actions":["Admin","Read","Write","List"]}]}' > ~/.cache/file-storage-test/s3.json
docker run -d --name file-storage-test-s3 -p 127.0.0.1:8961:8333 -v ~/.cache/file-storage-test/s3.json:/etc/s3.json:ro \
  chrislusf/seaweedfs server -s3 -s3.config=/etc/s3.json
initdb -D ~/.cache/file-storage-test/pg -A trust -U "$USER"
pg_ctl -D ~/.cache/file-storage-test/pg -o "-p 5499 -k $HOME/.cache/file-storage-test -c listen_addresses=localhost" \
  -l ~/.cache/file-storage-test/pg.log start
FILE_STORAGE_TEST_S3_ENDPOINT=http://127.0.0.1:8961 FILE_STORAGE_TEST_S3_ACCESS_KEY=file-storage-test-key \
FILE_STORAGE_TEST_S3_SECRET_KEY=file-storage-test-secret FILE_STORAGE_TEST_PG_PORT=5499 FILE_STORAGE_TEST_PG_USER=$USER \
  clojure -J-Djava.io.tmpdir=$HOME/.cache/file-storage-test/tmp -X:test
pg_ctl -D ~/.cache/file-storage-test/pg stop
```

The streaming test writes a 256 MB file (sparse) under `java.io.tmpdir`
and stores it from a JVM with a 64 MB heap. Cloudflare's purge is answered
by a stand-in server in the test.

Why SeaweedFS and not a fake:
[the decision](decisions/2026-10-23-tests-run-against-seaweedfs-and-postgres.md).
Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
