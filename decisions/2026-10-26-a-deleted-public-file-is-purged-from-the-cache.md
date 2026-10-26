# A deleted public file is purged from Cloudflare's cache

> Since v0.2.0. Changes in part [A file is deleted only when no row names it](2026-10-23-a-file-is-deleted-only-when-no-row-names-it.md) (a purge after the delete), [Every value that sets behaviour is a setting](2026-10-23-every-value-that-sets-behaviour-is-a-setting.md) (`:cache-purge`) and [Secrets are safe's wrapped secrets](2026-10-23-secrets-are-safes-wrapped-secrets.md) (the API token).

**Decision:** a store with a public address purges a deleted file's address from Cloudflare's cache after it deletes the object. The setting `:cache-purge` says how, written out on every store:

- `nil` on a private store (`:public-url nil`): it has no public address, and neither a zone nor a token;
- `{:api-url "https://api.cloudflare.com/client/v4" :zone-id "<32 hex digits>" :api-token #secret :groundwork-file-storage/cloudflare-token}` on a public store behind Cloudflare; the token is a groundwork-settings secret, revealed only in the request's Authorization header;
- `:not-cached` on a public store that nothing caches.

A private store with a purge, or a public one without its word, is refused at `make-file-storage`, named. When `delete-file!` deletes a file (`:deleted`), it sends `POST <api-url>/zones/<zone-id>/purge_cache {"files":["<public-url>/<key>"]}` through the store's one http client, with its timeouts and `:retries`; Cloudflare's `{"success":true}` is `purged`. Otherwise it throws `:groundwork-file-storage/cache-purge-failed` with Cloudflare's status and errors, the file already deleted (row and object): `delete-file-after-commit!`'s failure is written to the transaction's log, `delete-unnamed-files!` stops there. Counted, `groundwork_file_storage_cache_purges_total{outcome}` (`purged`, `failed`).

**Why:** without the purge, a deleted public file keeps being served from the cache until it expires there; a content-named file is cached for as long as Cloudflare keeps it. The object first, then the purge: purged first, the next request could fill the cache again from the object.

**The price:** a purge that fails leaves the file served from the cache until it expires there; the error says which address, for the app to purge again. A Cloudflare outage stops a sweep of `delete-unnamed-files!` at its first deleted public file.

**Instead of:**
- no purge: a deleted file served on
- the purge as a background job: a second record of what to purge, for a request the delete can make itself
- a purge failure only logged: the caller wouldn't know a deleted file is still public
- `:public-url` alone meaning Cloudflare: a public bucket that nothing caches, or another CDN, would need a token it doesn't have
