# One files table per store

> Since v0.2.0. Changes in part [Every file stored is a row of `attic.files`](2026-10-23-every-file-is-a-row-of-attic-files.md) (the table is the store's own, named by a setting) and [Every value that sets behaviour is a setting](2026-10-23-every-value-that-sets-behaviour-is-a-setting.md) (`:table`).

**Decision:** each store has its own files table, named by its setting `:table`, a qualified keyword `:groundwork-file-storage/<name>`: `:groundwork-file-storage/files` is `groundwork_file_storage.files`, `:groundwork-file-storage/catalog-files` is `groundwork_file_storage.catalog_files`. Every files table is in groundwork-file-storage's schema; another namespace is refused (`:groundwork-file-storage/bad-settings`). The columns' keys are the table's name and the field, `:catalog-files/key`, `:catalog-files/sha256`, `:catalog-files/size`, `:catalog-files/content-type`, `:catalog-files/stored-at` (`catalog_files__key` …), so an app's row names one store's files through a reference to that store's key column: `[:product/model-key :string {:references {:to :catalog-files/key …}}]`. `files-table` takes the store, or its settings before the store is made, and gives that table's map. The store makes its queries from the same name, as text (groundwork-postgres's `make-query-from-sql-text`), and every one answers a row as `#:file{:key :sha256 :size :content-type :stored-at}`, whichever store's it is. `find-file` and `list-files` take the store first: `(find-file store pool key)`, `(list-files store pool options)`.

Two stores in one database never see each other's rows. Iron Rainbow has three: its private files, its public files, and the catalog's.

**Why:** a catalog row must name a catalog file, and Postgres says so by itself when the reference points at the catalog's own table. The column keys carry the table's name because groundwork-postgres finds a reference's table by the key of its one-column primary key: two tables with `:file/key` as their primary key would be one target. One schema for every files table keeps those names unique: two tables with the same name can't be in two schemas.

**The price:** the app's references name a store's table (`:files/key`, `:catalog-files/key`), not one `:file/key` for all; the rows the store answers keep `:file/*`, so the column keys and the row keys differ. A table renamed is a store's data moved, by a groundwork-postgres change, not a setting changed.

**Instead of:**
- one table with a store column: every foreign key two columns (the store and the key), and "a catalog row names only catalog files" a check written by hand
- `:file/*` column keys in every table: references can't tell the tables apart
- files tables in any schema: `:a/files` and `:b/files` would share `:files/key`
