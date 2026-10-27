# attic decisions

One file per decision: `YYYY-MM-DD-what-we-decided.md`. The title is the decision. Each file says what we decided, why, and what we chose against. A decision changes by a new file; the old one says so at the top (`Replaced by` or `Changed in part by`), and nothing else in it is rewritten.

attic is groundwork's file storage (groundwork: the libraries and the composer of the same name that puts them together). The first were decided as v0.1.0 was built, from Iron Rainbow's R2 and catalog file code and what it taught; those of 2026-10-26 (v0.2.0), as Iron Rainbow was ported onto groundwork.

- [attic is its own library](2026-10-23-attic-is-its-own-library.md), changed in part by [The bucket through the JDK's http client](2026-10-26-the-bucket-through-the-jdks-http-client.md) and [The bucket through the AWS SDK for Java v2](2026-10-27-the-bucket-through-the-aws-sdk-for-java-v2.md)
- [A file is named by its content; an object is never written over](2026-10-23-a-file-is-named-by-its-content.md)
- [Every file stored is a row of `attic.files`](2026-10-23-every-file-is-a-row-of-attic-files.md), changed in part by [A file is deleted only when no row names it](2026-10-23-a-file-is-deleted-only-when-no-row-names-it.md) and [One files table per store](2026-10-26-one-files-table-per-store.md)
- [The bucket through aws-api; signed links written by attic](2026-10-23-the-bucket-through-aws-api-links-signed-by-attic.md), replaced by [The bucket through the JDK's http client](2026-10-26-the-bucket-through-the-jdks-http-client.md)
- [A file's kind is told by its bytes; the caller says which kinds it takes](2026-10-23-a-files-kind-is-told-by-its-bytes.md), changed in part by [The app's own kinds are data](2026-10-26-the-apps-own-kinds-are-data.md)
- [Uploads go through the server; the app says who may upload](2026-10-23-uploads-go-through-the-server.md)
- [Every value that sets behaviour is a setting; no hidden defaults](2026-10-23-every-value-that-sets-behaviour-is-a-setting.md), changed in part by [Secrets are safe's wrapped secrets](2026-10-23-secrets-are-safes-wrapped-secrets.md) and the decisions of 2026-10-26
- [The app passes its log and its meter](2026-10-23-the-app-passes-its-log-and-meter.md), changed in part by the decisions of 2026-10-26
- [The tests run against a real S3 API (SeaweedFS) and a real Postgres](2026-10-23-tests-run-against-seaweedfs-and-postgres.md), changed in part by the decisions of 2026-10-26
- [Secrets are safe's wrapped secrets](2026-10-23-secrets-are-safes-wrapped-secrets.md), changed in part by [The bucket through the JDK's http client](2026-10-26-the-bucket-through-the-jdks-http-client.md), [A deleted public file is purged from Cloudflare's cache](2026-10-26-a-deleted-public-file-is-purged-from-the-cache.md) and [The bucket through the AWS SDK for Java v2](2026-10-27-the-bucket-through-the-aws-sdk-for-java-v2.md)
- [A file is deleted only when no row names it; Postgres's references say so](2026-10-23-a-file-is-deleted-only-when-no-row-names-it.md), changed in part by [One files table per store](2026-10-26-one-files-table-per-store.md), [A deleted public file is purged from Cloudflare's cache](2026-10-26-a-deleted-public-file-is-purged-from-the-cache.md) and [A store that never deletes](2026-10-26-a-store-that-never-deletes.md)
- [One files table per store](2026-10-26-one-files-table-per-store.md)
- [The app's own kinds are data, given to the store](2026-10-26-the-apps-own-kinds-are-data.md)
- [A file on disk is stored streamed, never held whole](2026-10-26-a-file-on-disk-is-streamed-to-the-bucket.md), changed in part by [The bucket through the AWS SDK for Java v2](2026-10-27-the-bucket-through-the-aws-sdk-for-java-v2.md)
- [The bucket through the JDK's http client; SigV4 written by groundwork-file-storage](2026-10-26-the-bucket-through-the-jdks-http-client.md), replaced by [The bucket through the AWS SDK for Java v2](2026-10-27-the-bucket-through-the-aws-sdk-for-java-v2.md)
- [A link's lifetime and download name are the call's; the store caps them](2026-10-26-a-links-lifetime-and-download-name-are-the-calls.md)
- [A file is copied between stores by the bucket](2026-10-26-a-file-is-copied-between-stores-by-the-bucket.md)
- [A deleted public file is purged from Cloudflare's cache](2026-10-26-a-deleted-public-file-is-purged-from-the-cache.md), changed in part by [The bucket through the AWS SDK for Java v2](2026-10-27-the-bucket-through-the-aws-sdk-for-java-v2.md)
- [A store that never deletes](2026-10-26-a-store-that-never-deletes.md)
- [The bucket through the AWS SDK for Java v2](2026-10-27-the-bucket-through-the-aws-sdk-for-java-v2.md) (v0.2.1), changed in part by [A retry wait of 0 sends again at once](2026-10-27-a-retry-wait-of-0-sends-again-at-once.md)
- [A retry wait of 0 sends again at once](2026-10-27-a-retry-wait-of-0-sends-again-at-once.md) (v0.2.3)
