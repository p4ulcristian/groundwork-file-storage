# The app's own kinds are data, given to the store

> Since v0.2.0. Changes in part [A file's kind is told by its bytes; the caller says which kinds it takes](2026-10-23-a-files-kind-is-told-by-its-bytes.md) (the app declares kinds of its own) and [Every value that sets behaviour is a setting](2026-10-23-every-value-that-sets-behaviour-is-a-setting.md) (`:kinds`, `:head-bytes`).

**Decision:** groundwork-file-storage keeps its built-in kinds, told by their bytes, markup never one of them (PNG, JPEG, GIF, WebP, AVIF, Radiance HDR, PDF, glTF). `make-file-storage` takes the app's own kinds as an option, `:kinds`, `[]` for none, each a map with every key written out:

```clojure
{:content-type "model/step"
 :extension    "step"
 :recognize    (fn [{:keys [head file-name size open-content]}] true-or-false)
 :served-as    :download}
```

- `:recognize` is handed the content's first bytes (`:head`, at most the store's setting `:head-bytes`, at least 1024), its file name or nil (for a format told by its name too), its size (a binary STL is told by it), and `:open-content`, `(fn [] input-stream)` of the whole content, for a kind that must read all of it to say yes (an SVG checked clean by the app's cleaner: no script, no event attributes, no external references, no foreignObject). The browser's declared type is never handed in.
- `:served-as :inline` is sent as it is; `:download` is stored with `Content-Disposition: attachment` on the object, so a browser saves it rather than shows it, through a signed link or the public address alike.
- The built-in kinds are asked first, then the app's, in its order; the first that says yes is the content's kind, and gives the key's extension and the object's content type.
- A kind declared with a built-in kind's content type is refused, and one declared twice (`:groundwork-file-storage/bad-options`, every one named).

`known-content-types` takes the store: the built-in kinds' and its app's. `store-file!`'s and the upload's `:allowed-content-types` are sets of those.

Iron Rainbow will declare SVG (through its own cleaner), HEIC, STEP, IGES, STL, OBJ, 3MF, DXF, ZIP, XLSX, XLS, DOCX, DOC, CSV, TXT, MP4, M4A, MP3, KTX2 and others. The library ships none of those: it runs what it is given.

**Why:** which files an app takes is the app's, and the formats are many; each needs its own test of its bytes, which the app knows better than a library release cycle. A kind as a map is data: the composer can show its content type, extension and how it is served; only the recognizer is code, and it is handed what the library read, never the uploader's word. A built-in content type taken by an app kind would make the same type mean two tests.

**The price:** a recognizer the app writes can be wrong, and the library runs it as given; an inline kind the app declares (an SVG) is safe only as its cleaner is. A recognizer that reads the whole content reads it once more.

**Instead of:**
- the library learning every kind itself: a release for every format an app needs, and formats (DXF, OBJ, CSV) with no reliable first bytes decided once for every app
- converting a file to a kind the library knows, or refusing it: a STEP or a ZIP can't be converted, and refusing them leaves the app's real files out
- the browser's declared type for the kinds the library doesn't know: the uploader's word
