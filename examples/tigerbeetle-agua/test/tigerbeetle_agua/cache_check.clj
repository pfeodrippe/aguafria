(ns tigerbeetle-agua.cache-check
  "Fresh ordinary-JVM verification of prepared TigerBeetle walkthrough calls."
  (:require [aguafria.zig :as a]
            [aguafria.zig.artifact :as artifact]
            [aguafria.zig.explain :as explain]
            [aguafria.zig.precompile :as precompile]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io StringWriter]
           [java.nio.file Files FileVisitResult SimpleFileVisitor]))

(def ^:private selected-namespaces
  #{'tigerbeetle-agua.hot-reload-leaf
    'tigerbeetle-agua.hot-reload-target
    'tigerbeetle-agua.hot-reload-queue-target
    'tigerbeetle.src.vsr
    'tigerbeetle.src.tigerbeetle.main})

(defn- check-coverage! [report]
  (let [coverage (:coverage report)
        computed (precompile/coverage (:analysis report))
        functions (mapcat :functions (:analysis report))]
    (assert (= computed
               (select-keys (update coverage :namespaces dissoc :ignored)
                            (keys computed)))
            "Coverage totals must match the actual operation records")
    (assert (= selected-namespaces (set (map :namespace (:analysis report))))
            "Preparation must include the five selected namespaces")
    (assert (= (count selected-namespaces) (count (:analysis report))
               (get-in coverage [:namespaces :attempted])))
    (assert (zero? (get-in coverage [:namespaces :baseline-failures])))
    (assert (= {:analyzed (count selected-namespaces)}
               (get-in coverage [:namespaces :statuses]))
            (artifact/print-data (:namespaces coverage)))
    (assert (= (get-in coverage [:runtime-candidates :total])
               (get-in coverage [:runtime-candidates :fully-prepared]))
            (artifact/print-data (:runtime-candidates coverage)))
    (assert (every? #(or (= :prepared (:status %))
                         (and (= :skipped (:status %))
                              (#{:specialization :test-runner :process-entry :comptime-result}
                               (:reason %))))
                    functions)
            (artifact/print-data (:declared-functions coverage)))
    (assert (= (count functions) (get-in coverage [:declared-functions :total])))
    (assert (= (frequencies (map :status functions))
               (get-in coverage [:declared-functions :statuses])))
    (assert (seq (get-in report [:scalar-constructor-profiles :types])))
    (assert (seq (get-in report [:scalar-constructor-profiles :statuses])))
    (assert (every? #{:prepared}
                    (keys (get-in report [:scalar-constructor-profiles :statuses])))
            (artifact/print-data (:scalar-constructor-profiles report)))))

(defn- native-libraries [cache]
  (let [root (.toPath (io/file cache))
        paths (atom #{})]
    (Files/walkFileTree
     root #{} 3
     (proxy [SimpleFileVisitor] []
       (preVisitDirectory [path _]
         (if (and (= 1 (.getNameCount (.relativize root path)))
                  (= "dependencies" (str (.getFileName path))))
           FileVisitResult/SKIP_SUBTREE
           FileVisitResult/CONTINUE))
       (visitFile [path attributes]
         (when (and (.isRegularFile attributes)
                    (some #(str/ends-with? (str (.getFileName path)) %)
                          [".dylib" ".so" ".dll"]))
           (swap! paths conj (str path)))
         FileVisitResult/CONTINUE)))
    @paths))

(defn- read-and-close [native]
  (try (a/value native) (finally (a/close! native))))

(defn- pure-call-checks []
  (let [leaf (requiring-resolve 'tigerbeetle-agua.hot-reload-target/leaf-caller)
        comptime (requiring-resolve 'tigerbeetle-agua.hot-reload-target/comptime-caller)
        queue-size (requiring-resolve 'tigerbeetle-agua.hot-reload-queue-target/queue-size)
        floor (requiring-resolve 'tigerbeetle.src.vsr/sector_floor)
        ceil (requiring-resolve 'tigerbeetle.src.vsr/sector_ceil)
        checks (atom 0)
        check! (fn [expected actual]
                 (swap! checks inc)
                 (assert (= expected actual)
                         (artifact/print-data {:expected expected :actual actual})))]
    (check! 10 (read-and-close (leaf)))
    (check! 10 (read-and-close (comptime)))
    (let [size (read-and-close (queue-size))]
      (assert (pos? size))
      (swap! checks inc)
      (check! size (read-and-close (queue-size))))
    (doseq [[offset below above] [[0 0 0] [1 0 4096] [4095 0 4096]
                                  [4096 4096 4096] [4097 4096 8192]
                                  [8192 8192 8192]]]
      (check! below (read-and-close (floor offset)))
      (check! above (read-and-close (ceil offset))))
    {:checks @checks}))

(defn check!
  "Require the walkthrough normally, then check results, exact keys and library
  writes. Run in a fresh JVM with test on its classpath after preparation.
  Incomplete coverage is rejected before calling or warming native handlers."
  [prepared-report result-file]
  (assert (not-any? find-ns '[tigerbeetle-agua.hot-reload-target
                              tigerbeetle-agua.hot-reload-queue-target
                              tigerbeetle.src.vsr])
          "Run the cache checker before loading the walkthrough")
  (let [report (edn/read-string (slurp prepared-report))
        _ (check-coverage! report)
        _ (assert (= 1 (count (get-in report [:bundles :packs]))))
        pack-id (get-in report [:bundles :packs 0 :id])
        cache (:cache-dir report)
        _ (assert (= cache (:cache-dir (a/configuration))))
        entries (set (keys (:entries
                            (edn/read-string
                             (slurp (io/file cache "bundles" pack-id "manifest.edn"))))))
        _ (assert (seq entries))
        standalone (into #{} (keep :artifact-id) (get-in report [:bundles :standalone]))
        before (native-libraries cache)
        events (atom [])
        output (StringWriter.)
        started (System/nanoTime)
        checked (binding [*out* output *err* output
                          explain/*reporter* #(swap! events conj %)]
                  (require 'tigerbeetle-agua.hot-reload-target
                           'tigerbeetle-agua.hot-reload-queue-target
                           'tigerbeetle.src.vsr)
                  (pure-call-checks))
        duration-ms (/ (- (System/nanoTime) started) 1e6)
        hits (filterv #(= :bundle-cache-hit (:event %)) @events)
        key-for #(artifact/key-for :bundle-entry [(:module %) (:artifact-key %)])
        unmatched (filterv #(or (not (entries (key-for %)))
                                (not= pack-id (:bundle-id %))) hits)
        unbundled (filterv #(and (= :disk-cache-hit (:event %))
                                 (str/starts-with? (or (:module %) "") "aguafria.jvm.")
                                 (not (standalone (key-for %)))) @events)
        requested (into #{} (mapcat #(remove nil? [(:artifact-key %) (:bundle-id %)]))
                        @events)
        writes (remove before (native-libraries cache))
        ours? #(requested (.getName (.getParentFile (io/file %))))
        result {:pure checked
                :coverage (:coverage report)
                :duration-ms duration-ms
                :events (frequencies (map :event @events))
                :artifact-events (filterv :artifact-key @events)
                :compiled (filterv #(#{:compiled :compile-failed} (:event %)) @events)
                :unmatched-bundle-hits unmatched
                :unbundled-handlers unbundled
                :new-libraries (filterv ours? writes)
                :concurrent-cache-writes (filterv #(not (ours? %)) writes)
                :output (str output)}]
    (io/make-parents result-file)
    (spit result-file (artifact/print-data result))
    (assert (= 16 (get-in result [:pure :checks])))
    (assert (empty? (:compiled result)) (artifact/print-data (:compiled result)))
    (assert (seq hits))
    (assert (= 1 (get-in result [:events :bundle-loaded])))
    (assert (empty? unmatched) (artifact/print-data unmatched))
    (assert (empty? unbundled) (artifact/print-data unbundled))
    (assert (empty? (:new-libraries result)) (artifact/print-data (:new-libraries result)))
    result))
