(ns groundwork-file-storage.links
  "Links a browser reads a file through.

   A signed link: an S3 GET with SigV4's query-string signature, made by
   the AWS SDK's S3Presigner and signed by its SigV4 signer
   (groundwork-file-storage.bucket/presign-get). Anyone holding it reads
   that one file until it expires; a private bucket stays private.
   Path-style, on the settings' :endpoint:
   <endpoint>/<bucket>/<key>?X-Amz-…&X-Amz-Signature=…

   How long it lives is the call's: [:from-now seconds], signed now; or
   [:window seconds], signed at the start of the current window and
   expiring at the end of the next, so every link to a file made within
   one window is the same address, which a browser caches. :download-as,
   a file name, has the answer say Content-Disposition: attachment with
   that name (response-content-disposition, signed with the rest).

   A public link: the settings' :public-url and the key, for a bucket (or a
   CDN in front of it) every browser may read."
  (:require
   [clojure.string :as str]
   [groundwork-file-storage.bucket :as bucket])
  (:import
   (java.time Instant)
   (software.amazon.awssdk.utils.http SdkHttpUtils)))

(defn find-signing-time-and-lifetime
  "[the instant the link is signed at, how many seconds it reads] for
   the link's :expiry at now."
  [[shape seconds] ^Instant now]
  (case shape
    :from-now [now seconds]
    :window   [(Instant/ofEpochSecond (* seconds (quot (.getEpochSecond now) seconds))) (* 2 seconds)]))

(defn find-lifetime
  "How many seconds a link with this :expiry reads its file, at most."
  [[shape seconds]]
  (case shape
    :from-now seconds
    :window   (* 2 seconds)))

(defn write-content-disposition
  "attachment; filename=\"<the name, ASCII>\"; filename*=UTF-8''<the name,
   percent-encoded> (RFC 6266): a browser that reads filename* takes the
   name as it is, any other the ASCII one, every character outside
   printable ASCII, \" \\ and % made _."
  [file-name]
  (str "attachment; filename=\"" (str/replace file-name #"[^\x20-\x7E]|[\"\\%]" "_") "\""
       "; filename*=UTF-8''" (SdkHttpUtils/urlEncode file-name)))

(defn make-signed-link
  "A signed GET link to the object at the key (see the namespace), by the
   store's presigner; options {:expiry :download-as}, checked by the
   caller."
  [presigner settings object-key {:keys [expiry download-as]} ^Instant now]
  (let [[signed-at lifetime] (find-signing-time-and-lifetime expiry now)]
    (bucket/presign-get presigner settings object-key (when download-as (write-content-disposition download-as))
                        signed-at lifetime)))

(defn make-public-link
  "The public address of the object at the key; throws when the settings
   have no :public-url."
  [{:keys [public-url]} object-key]
  (when-not public-url
    (throw (ex-info "This file storage has no :public-url: its files are read through signed links (make-signed-link)"
                    {:type :groundwork-file-storage/no-public-url :object-key object-key})))
  (str public-url "/" (SdkHttpUtils/urlEncodeIgnoreSlashes object-key)))
