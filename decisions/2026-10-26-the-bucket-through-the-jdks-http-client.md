# The bucket through the JDK's http client; SigV4 written by groundwork-file-storage

> **Replaced** by [The bucket through the AWS SDK for Java v2](2026-10-27-the-bucket-through-the-aws-sdk-for-java-v2.md) (2026-10-27, v0.2.1): the SDK's S3Client and S3Presigner, on its Apache 5 http client; the SigV4 written here and its test vectors are deleted.

> Since v0.2.0. Replaces [The bucket through aws-api; signed links written by attic](2026-10-23-the-bucket-through-aws-api-links-signed-by-attic.md). Changes in part [attic is its own library](2026-10-23-attic-is-its-own-library.md) (no aws-api), [Secrets are safe's wrapped secrets](2026-10-23-secrets-are-safes-wrapped-secrets.md) (where the key is revealed) and [Every value that sets behaviour is a setting](2026-10-23-every-value-that-sets-behaviour-is-a-setting.md) (`:region`).

**Decision:** groundwork-file-storage talks to the bucket through the JDK's own `java.net.http.HttpClient`, one per store, and signs every request itself with AWS Signature Version 4 (`groundwork-file-storage.signing`): put, get, delete, list (ListObjectsV2) and copy (CopyObject), path-style at `:endpoint`, `<endpoint>/<bucket>/<key>`. cognitect's aws-api is dropped. The same client sends the cache purge ([its decision](2026-10-26-a-deleted-public-file-is-purged-from-the-cache.md)); there is no second one.

- **Signing.** The Authorization header for a request, its body's sha256 in `x-amz-content-sha256` (the content's own on a put, the empty body's otherwise); the query string for a link (`UNSIGNED-PAYLOAD`). A path is percent-encoded once, `/` kept, never normalized, as S3 wants. Tested against AWS's published SigV4 test suite (11 cases, both the header and the query signature of each).
- **The client.** HTTP/1.1, connecting within `:connect-timeout-ms`, each request waiting `:request-timeout-ms` for its answer, redirects never followed.
- **Retries, the same meaning as before.** An answer of 429, 503 or 504 (the statuses aws-api counted as busy), or a request that never connected (nothing was sent), is sent again by `:retries {:max-retries :first-wait-ms :max-wait-ms}`, waiting `:first-wait-ms`, twice as long each time, never longer than `:max-wait-ms`; each retry signed anew.
- **The bucket's answers.** `If-None-Match: *` stays on the put and the copy, 412 read as "there already"; an S3 `<Error>` gives its code and message to `:groundwork-file-storage/bucket-failed`, a CopyObject's `<Error>` inside a 200 too. XML is read by the JDK's parser, DOCTYPEs refused.
- **The region** is any text: requests and links are signed for it. R2 writes `"auto"`, as R2 says.

**Why:** aws-api reads every request body into one byte array (`cognitect/aws/util.clj`'s `->bbuf` turns an InputStream into a byte array), so it can't stream a put ([the decision](2026-10-26-a-file-on-disk-is-streamed-to-the-bucket.md)). Five operations and a signature are a small, fixed protocol, and the presigned links were already signed here. One client is one connection pool, one set of timeouts, one retry rule.

**The price:** SigV4 is groundwork-file-storage's to keep right; the test suite and SeaweedFS (which checks every signature) stand for it. S3's newer features (checksums other than sha256, SSE headers) are not sent.

**Instead of:**
- aws-api, as before: no streaming put, and its http client wrapped to get timeouts
- AWS SDK v2: it streams and presigns, and is a far larger dependency for five operations
- aws-api for the small requests beside a streaming client for the put: two clients, two retry rules, two places the key is revealed
