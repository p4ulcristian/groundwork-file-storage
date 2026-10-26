(ns groundwork-file-storage.metrics
  "groundwork-file-storage's metrics, declared on the app's meter by make-file-storage:

     groundwork_file_storage_files_stored_total{outcome}        counter: files stored, new or there already
     groundwork_file_storage_files_refused_total{reason}        counter: files refused, by why
     groundwork_file_storage_files_deleted_total{outcome}       counter: files asked to be deleted, by what happened
     groundwork_file_storage_files_copied_total{outcome}        counter: files copied into the store, new or there already
     groundwork_file_storage_cache_purges_total{outcome}        counter: deleted public files' addresses purged from the cache
     groundwork_file_storage_bucket_failures_total{operation}   counter: requests the bucket failed, after retries"
  (:require
   [groundwork-metrics.api :as metrics]))

(def outcomes
  "How a store ended:
   :stored          the bucket had no object at its key; it is put
   :already-stored  the same bytes were stored before: nothing put"
  #{:stored :already-stored})

(def reasons
  "Why a file was refused:
   :wrong-kind  its bytes are no content type the call allows
   :too-large   an upload's file larger than :max-file-bytes"
  #{:wrong-kind :too-large})

(def deletions
  "How a delete ended:
   :deleted      the row and the object are gone
   :still-named  a row of the app names the file (a reference to file__key): nothing deleted
   :not-found    no file has that key
   :refused      the store never deletes (:deletes? false): nothing deleted"
  #{:deleted :still-named :not-found :refused})

(def copies
  "How a copy into the store ended:
   :copied          the bucket copied the object; its row recorded
   :already-stored  the store had the file: nothing copied"
  #{:copied :already-stored})

(def purges
  "How a deleted public file's cache purge ended:
   :purged  Cloudflare purged its address
   :failed  it didn't, after the retries (thrown, :groundwork-file-storage/cache-purge-failed)"
  #{:purged :failed})

(def operations #{:store :read :delete :copy})

(defn declare-metrics! [meter]
  (metrics/declare-metrics!
   meter
   [{:name    :groundwork-file-storage/files-stored-total
     :type    :counter
     :help    "Files stored, by whether the bucket had them already (the same bytes are one file)."
     :labels  {:outcome outcomes}
     :buckets nil
     :read    nil}
    {:name    :groundwork-file-storage/files-refused-total
     :type    :counter
     :help    "Files refused, by why: their bytes are no kind allowed, or an upload's file is too large."
     :labels  {:reason reasons}
     :buckets nil
     :read    nil}
    {:name    :groundwork-file-storage/files-deleted-total
     :type    :counter
     :help    "Files asked to be deleted, by what happened: deleted, kept since a row still names them, no such file, or refused by a store that never deletes."
     :labels  {:outcome deletions}
     :buckets nil
     :read    nil}
    {:name    :groundwork-file-storage/files-copied-total
     :type    :counter
     :help    "Files copied into the store from another, by whether the store had them already."
     :labels  {:outcome copies}
     :buckets nil
     :read    nil}
    {:name    :groundwork-file-storage/cache-purges-total
     :type    :counter
     :help    "Deleted public files' addresses purged from the cache, by whether the purge worked."
     :labels  {:outcome purges}
     :buckets nil
     :read    nil}
    {:name    :groundwork-file-storage/bucket-failures-total
     :type    :counter
     :help    "Requests the bucket failed, after their retries, by operation."
     :labels  {:operation operations}
     :buckets nil
     :read    nil}]))

(defn count-stored! [meter outcome]
  (metrics/add-to-counter! meter :groundwork-file-storage/files-stored-total {:outcome outcome} 1))

(defn count-refused! [meter reason]
  (metrics/add-to-counter! meter :groundwork-file-storage/files-refused-total {:reason reason} 1))

(defn count-deleted! [meter outcome]
  (metrics/add-to-counter! meter :groundwork-file-storage/files-deleted-total {:outcome outcome} 1))

(defn count-copied! [meter outcome]
  (metrics/add-to-counter! meter :groundwork-file-storage/files-copied-total {:outcome outcome} 1))

(defn count-purge! [meter outcome]
  (metrics/add-to-counter! meter :groundwork-file-storage/cache-purges-total {:outcome outcome} 1))

(defn count-bucket-failure! [meter operation]
  (metrics/add-to-counter! meter :groundwork-file-storage/bucket-failures-total {:operation operation} 1))
