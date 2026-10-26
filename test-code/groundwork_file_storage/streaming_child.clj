(ns groundwork-file-storage.streaming-child
  "Run by api-test in a JVM of its own, with a heap smaller than the file
   it stores: store-file! from {:path …} must not hold the file. Prints
   the row and the largest heap used, as EDN, and exits 0; an
   OutOfMemoryError exits otherwise."
  (:require
   [groundwork-file-storage.api :as file-storage]
   [groundwork-file-storage.test-setup :as setup]
   [groundwork-postgres.api :as postgres])
  (:import
   (java.lang.management ManagementFactory MemoryPoolMXBean MemoryType)))

(defn- find-largest-heap-used
  "The heap pools' peak use, added up, in bytes."
  []
  (reduce + (for [^MemoryPoolMXBean pool (ManagementFactory/getMemoryPoolMXBeans)
                  :when (= MemoryType/HEAP (.getType pool))]
              (.getUsed (.getPeakUsage pool)))))

(defn -main [path]
  (let [pool  (setup/make-pool)
        store (setup/make-store (setup/make-settings) [])]
    (try
      (doseq [^MemoryPoolMXBean memory-pool (ManagementFactory/getMemoryPoolMXBeans)]
        (.resetPeakUsage memory-pool))
      (let [row (file-storage/store-file! store pool {:path path}
                                          {:key-prefix "streamed" :allowed-content-types #{"image/png"} :file-name nil})]
        (prn {:row                     (select-keys row [:file/key :file/sha256 :file/size])
              :largest-heap-used-bytes (find-largest-heap-used)
              :max-heap-bytes          (.maxMemory (Runtime/getRuntime))}))
      (finally
        (file-storage/stop-file-storage! store)
        (postgres/stop-pool! pool)))
    (shutdown-agents)
    (System/exit 0)))
