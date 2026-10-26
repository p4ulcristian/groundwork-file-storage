# A file is copied between stores by the bucket

> Since v0.2.0.

**Decision:** `(copy-file! from-store to-store pool-or-transaction key)` copies a file from one store to another: the bucket copies the object itself (S3's CopyObject, `x-amz-copy-source: /<from bucket>/<key>`), signed with the target store's key, which must read the source's bucket. The bytes never pass through the server. The key stays the same: same bytes, same key. The target's row is recorded on the pool or in the caller's transaction, with the source row's sha256, size and content type; the object stays if that rolls back, and is found again (412) by the next copy.

- The object is stored as the target's kinds serve its content type (`x-amz-metadata-directive: REPLACE`, its `Content-Disposition` from the target's kind), with `If-None-Match: *`.
- Refused: two stores on different endpoints (`:groundwork-file-storage/different-endpoints`), a key the source has no row for (`:groundwork-file-storage/no-such-file`), a content type the target has no kind for (`:groundwork-file-storage/wrong-kind`). A target that has the file answers its row. Both stores' tables are read through the one pool or transaction.
- Counted on the target's meter, `groundwork_file_storage_files_copied_total{outcome}` (`copied`, `already-stored`), a bucket failure as `groundwork_file_storage_bucket_failures_total{operation="copy"}`.

Iron Rainbow uses it when a retail partner publishes a product: its photos move from the private store to the public one.

**Why:** a product's photos are already stored and checked; publishing them is the same bytes in a bucket browsers may read. Through the server would download and upload each file for nothing. The same key keeps the key the app's rows already hold.

**The price:** both buckets are on one endpoint and one key reads both. A copy can't change the file (resize, clean): that is a new store of new bytes.

**Instead of:**
- reading the file and storing it in the other store: every byte twice through the server
- one bucket with a public prefix: the public part would be a rule of the bucket's policy, not of a store
