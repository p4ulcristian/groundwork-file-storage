# A store that never deletes

> Since v0.2.0. Changes in part [A file is deleted only when no row names it](2026-10-23-a-file-is-deleted-only-when-no-row-names-it.md) (a store may refuse every delete) and [Every value that sets behaviour is a setting](2026-10-23-every-value-that-sets-behaviour-is-a-setting.md) (`:deletes?`).

**Decision:** a store's setting `:deletes?`, written out on every store, no default. With `false`, `delete-file!`, `delete-file-after-commit!` and `delete-unnamed-files!` delete nothing, no row and no object: each refusal is written to the app's log, `:info :groundwork-file-storage/delete-refused` (the key, or the prefix), and counted, `groundwork_file_storage_files_deleted_total{outcome="refused"}`. `delete-file!` answers `:refused`; `delete-file-after-commit!` refuses once the transaction commits, as it would delete; `delete-unnamed-files!` answers `:refused` without reading a row. A transaction handed to `delete-file!` is still refused first (`:groundwork-file-storage/needs-a-pool`), so a store that never deletes catches the same mistakes as one that does.

**Why:** Iron Rainbow's dev and previews share stage's buckets; their rows are their own, the objects are stage's. A delete on dev would take a file stage still names. Refusing in the store keeps every caller's code the same on every copy of the app, and the log and the counter show what would have gone.

**The price:** a store that never deletes keeps every file it stored, and the shared bucket grows with dev's files.

**Instead of:**
- a read-only bucket key on dev: dev must store files too
- a default of true: a copy of the app sharing a bucket deletes until someone remembers the setting
- the app checking before each delete: every call site, and one forgotten
