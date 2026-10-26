# A link's lifetime and download name are the call's; the store caps them

> Since v0.2.0. Changes in part [Every value that sets behaviour is a setting](2026-10-23-every-value-that-sets-behaviour-is-a-setting.md) (`:link-expiry-seconds` is gone; `:longest-link-seconds`, and `make-signed-link`'s options) and [The bucket through aws-api; signed links written by attic](2026-10-23-the-bucket-through-aws-api-links-signed-by-attic.md) (a link's expiry).

**Decision:** `make-signed-link` takes options, every key required, checked first (`:groundwork-file-storage/bad-options`): `(make-signed-link store key {:expiry … :download-as …})`.

- **`:expiry`**, how long the link reads its file, one of two shapes:
  - `[:from-now seconds]`: signed now, read for that long (a few minutes for an outside service; 7 days for an email's download link);
  - `[:window seconds]`: signed at the start of the current window (the time rounded down to a multiple of the window, since 1970), expiring at the end of the next one, so it reads for between one and two windows. Every link to a file made within one window is the same address, which the browser caches.
- **`:download-as`**: nil, or a file name (1 to 255 characters, no control character, no `/` or `\`). The link carries `response-content-disposition`, part of what is signed, and the bucket answers `Content-Disposition: attachment; filename="<the name, ASCII>"; filename*=UTF-8''<the name, percent-encoded>` (RFC 6266): a browser that reads `filename*` takes the name as it is; for any other, every character outside printable ASCII, `"`, `\` and `%` is `_`.
- **The store's cap**, the setting `:longest-link-seconds` (1 to 604800, S3's longest): a link that would read longer (a window's twice the window) is refused, named.

**Why:** how long a link should live is the reader's: an outside service's minutes, an email's week, a page's images that should cache. One store-wide expiry made every link the longest any of them needed. A link signed now is a new address every time, so a page of images was fetched anew on every view; a window's link is one address for the window, and still expires. The name a file downloads as is the app's row's ("Invoice 2026-104.pdf"); the key is a uuid. The cap keeps what the app may hand out a store's decision, written once.

**The price:** a window's link lives up to twice its window, not exactly it. A download name changes the address, so each name is cached apart.

**Instead of:**
- `:link-expiry-seconds` for every link of a store: one lifetime for every use
- `{:expires-at instant}`: no shared address between calls, and the caller does the rounding
- a window's link expiring at its own window's end: a link made just before the end would read for a moment
- the name sent by the app's server as a header: the browser reads the bucket, not the server
