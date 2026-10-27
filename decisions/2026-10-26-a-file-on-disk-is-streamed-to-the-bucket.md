# A file on disk is stored streamed, never held whole

> **Changed in part** by [The bucket through the AWS SDK for Java v2](2026-10-27-the-bucket-through-the-aws-sdk-for-java-v2.md) (2026-10-27, v0.2.1): the put is the SDK's, from `RequestBody.fromFile`; what the bucket checks is the `Content-MD5` of the bytes hashed, computed in the same read as the sha256 (a file that changed is refused `BadDigest`), not the sha256 in `x-amz-content-sha256`.

> Since v0.2.0. Changes in part [Every value that sets behaviour is a setting](2026-10-23-every-value-that-sets-behaviour-is-a-setting.md) (`store-file!` takes its content as a map).

**Decision:** `store-file!` takes the content as `{:path "/a/file/on/disk"}` or `{:bytes byte-array}`, checked with the options, every problem at once (`:groundwork-file-storage/bad-options`; a path that is no readable file is named under `:content`). From a path:

1. the head is read for the kind check (`:head-bytes` of it);
2. the whole file is hashed in pieces, a 64 KiB buffer at a time, for its sha256 (the key) and its size;
3. the object is put from the file (the JDK's `BodyPublishers.ofFile`), with its known length, its sha256 in `x-amz-content-sha256`, `If-None-Match: *`.

Memory per store is a buffer, whatever the file's size. The bucket checks the sha256 it is sent against the bytes it gets: a file that changed between its hashing and its put is refused (`XAmzContentSHA256Mismatch`, `:groundwork-file-storage/bucket-failed`), never stored under another content's key. A retry reads the file again.

The test proves it: a JVM with a 64 MB heap stores a 256 MB file, and its row and object are checked (its sha256 and size against the file's, the bucket's listing).

**Why:** Iron Rainbow's uploads go to disk first (a 100 MB STEP, a video); read into a byte array, every upload at once would grow the heap by its size. The sha256 is needed before the put (it names the key), so hashing first costs one read of the file and gives the bucket a check for free.

**The price:** a file on disk is read twice (hashing, then the put). An object larger than S3's single put (5 GB) can't be stored.

**Instead of:**
- the bytes in memory: memory grows with every upload at once
- S3's multipart upload in parts: only needed beyond 5 GB (Iron Rainbow's largest file is 100 MB), and a second protocol (start, parts, complete, abort, and parts left behind) for sizes no app here has
- `UNSIGNED-PAYLOAD` on the put: the bucket wouldn't catch a file that changed while it was stored
