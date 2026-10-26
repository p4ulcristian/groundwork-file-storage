(ns groundwork-file-storage.kinds
  "What kind of file some content is, from its bytes, never from what the
   browser says. A kind is data:

     {:content-type \"model/step\"
      :extension    \"step\"
      :recognize    (fn [{:keys [head file-name size open-content]}] true-or-false)
      :served-as    :download}

   :recognize is handed the content's first bytes (:head, at most the
   store's :head-bytes), its file name or nil, its size, and
   :open-content, (fn [] input-stream) of the whole content (the caller
   closes it), for a kind that must read all of it to say yes (an SVG
   checked clean). :served-as :inline is sent as it is; :download is
   stored with Content-Disposition: attachment, so a browser saves it
   rather than shows it.

   groundwork-file-storage's own kinds come first, each told by the first
   bytes every file of its format starts with; markup (SVG, HTML) is none
   of them. The app's kinds, make-file-storage's :kinds, come after, in
   their order; the first kind that says yes is the content's."
  (:require
   [clojure.string :as str])
  (:import
   (java.nio.charset StandardCharsets)
   (java.util Arrays)))

(defn- does-start-with? [^bytes bs offset signature]
  (and (>= (alength bs) (+ offset (count signature)))
       (every? true? (map-indexed (fn [i b] (= (unchecked-byte b) (aget bs (+ offset i)))) signature))))

(defn- turn-head-into-text
  "The first kilobyte as lower-case text, a byte-order mark and leading
   space left out."
  [^bytes bs]
  (-> (String. (Arrays/copyOf bs (int (min 1024 (alength bs)))) StandardCharsets/UTF_8)
      (str/replace "﻿" "")
      str/triml
      str/lower-case))

(defn- find-extension [file-name]
  (some-> file-name (str/split #"\.") last str/lower-case))

(defn- make-kind [content-type extension recognize]
  {:content-type content-type :extension extension :recognize recognize :served-as :inline})

(def built-in-kinds
  "The kinds groundwork-file-storage tells itself, every one served inline."
  [(make-kind "image/png" "png" (fn [{:keys [head]}] (does-start-with? head 0 [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A])))
   (make-kind "image/jpeg" "jpg" (fn [{:keys [head]}] (does-start-with? head 0 [0xFF 0xD8 0xFF])))
   (make-kind "image/gif" "gif" (fn [{:keys [head]}] (does-start-with? head 0 (map int "GIF8"))))
   (make-kind "image/webp" "webp" (fn [{:keys [head]}] (and (does-start-with? head 0 (map int "RIFF"))
                                                            (does-start-with? head 8 (map int "WEBP")))))
   (make-kind "image/avif" "avif" (fn [{:keys [head]}] (and (does-start-with? head 4 (map int "ftyp"))
                                                            (does-start-with? head 8 (map int "avif")))))
   (make-kind "model/gltf-binary" "glb" (fn [{:keys [head]}] (does-start-with? head 0 (map int "glTF"))))
   (make-kind "application/pdf" "pdf" (fn [{:keys [head]}] (does-start-with? head 0 (map int "%PDF-"))))
   (make-kind "image/vnd.radiance" "hdr" (fn [{:keys [head]}] (let [text (turn-head-into-text head)]
                                                                (or (str/starts-with? text "#?radiance")
                                                                    (str/starts-with? text "#?rgbe")))))
   ;; JSON: a .gltf only by its name
   (make-kind "model/gltf+json" "gltf" (fn [{:keys [head file-name]}] (and (= "gltf" (find-extension file-name))
                                                                           (str/starts-with? (turn-head-into-text head) "{"))))])

(def built-in-content-types
  (set (map :content-type built-in-kinds)))

(defn find-kind
  "The first of the kinds that recognizes the content
   ({:head :file-name :size :open-content}), or nil."
  [kinds content]
  (first (filter #((:recognize %) content) kinds)))
