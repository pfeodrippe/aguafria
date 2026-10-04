(ns racing-game.cache-check
  "Fresh ordinary-JVM acceptance of the game's prepared native artifacts."
  (:require [aguafria.zig :as a]
            [aguafria.zig.artifact :as artifact]
            [aguafria.zig.explain :as explain]
            [aguafria.zig.precompile :as precompile]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test])
  (:import [java.io StringWriter]
           [java.nio.file Files FileVisitResult SimpleFileVisitor]))

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

(defn- selected-namespaces []
  (into #{}
        (comp
         (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")))
         (map (fn [file]
                (with-open [reader (java.io.PushbackReader. (io/reader file))]
                  (let [form (binding [*read-eval* false] (read reader))]
                    (assert (= 'ns (first form)) (str file))
                    (second form)))))
         (remove #{'racing-game.shaders}))
        (file-seq (io/file "src/racing_game"))))

(defn- check-coverage! [report]
  (let [coverage (:coverage report)
        computed (precompile/coverage (:analysis report))
        functions (mapcat :functions (:analysis report))
        selected (selected-namespaces)]
    (assert (= computed
               (select-keys (update coverage :namespaces dissoc :ignored)
                            (keys computed)))
            "Coverage totals must match the actual operation records")
    (assert (= selected (set (map :namespace (:analysis report))))
            "Preparation must include every host source namespace")
    (assert (= (count selected) (count (:analysis report))
               (get-in coverage [:namespaces :attempted])))
    (assert (zero? (get-in coverage [:namespaces :baseline-failures])))
    (assert (every? #{:analyzed :skipped}
                    (keys (get-in coverage [:namespaces :statuses])))
            (artifact/print-data (:namespaces coverage)))
    (assert (= (get-in coverage [:runtime-candidates :total])
               (get-in coverage [:runtime-candidates :fully-prepared]))
            (artifact/print-data (:runtime-candidates coverage)))
    (assert (= (get-in coverage [:declared-functions :total])
               (get-in coverage [:declared-functions :statuses :prepared]))
            (artifact/print-data (:declared-functions coverage)))
    (assert (every? #(= :prepared (:status %)) functions)
            (artifact/print-data (:declared-functions coverage)))
    (assert (= (count functions) (get-in coverage [:declared-functions :total])))
    (assert (seq (get-in report [:scalar-constructor-profiles :types])))
    (assert (seq (get-in report [:scalar-constructor-profiles :statuses])))
    (assert (every? #{:prepared}
                    (keys (get-in report [:scalar-constructor-profiles :statuses])))
            (artifact/print-data (:scalar-constructor-profiles report)))))

(defn- read-and-close [native]
  (try
    (a/value native)
    (finally (a/close! native))))

(defn- pure-call-checks []
  (let [cadence (requiring-resolve 'racing-game.simulation/cadence-ticks-for-pressure)
        deadline (requiring-resolve 'racing-game.simulation/decision-deadline-ticks)
        expired? (requiring-resolve 'racing-game.simulation/decision-expired?)
        speed (requiring-resolve 'racing-game.circuit/kilometres-per-hour)
        checks (atom 0)
        check! (fn [expected actual]
                 (swap! checks inc)
                 (assert (= expected actual)
                         (artifact/print-data {:expected expected :actual actual})))]
    (doseq [[pending latency expected] [[0 0 40] [5 0 48] [7 0 60]
                                        [0 250000 60] [0 333000 80]]]
      (check! expected (read-and-close (cadence pending latency))))
    (doseq [[urgent expected] [[false 720] [true 600]]]
      (check! expected (read-and-close (deadline urgent))))
    (doseq [[tick urgent expected] [[609 true false] [610 true true]
                                    [729 false false] [730 false true]]]
      (check! expected (read-and-close (expired? 10 tick urgent))))
    (check! 36.0 (double (read-and-close (speed 10.0))))
    {:checks @checks}))

(defn check!
  "Require the game normally and verify pure calls plus lap-clock tests.
  Run in a fresh JVM with test on the classpath, after whole-project preparation.
  Saves failed cache evidence before rejecting a miss or unapproved standalone
  handler. Other concurrently written artifacts are reported separately."
  [prepared-report result-file]
  (assert (not-any? find-ns '[racing-game.simulation racing-game.circuit
                              racing-game.lap-timing-test])
          "Run the cache checker before loading the game")
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
        checked (binding [*out* output *err* output test/*test-out* output
                          explain/*reporter* #(swap! events conj %)]
                  (require 'racing-game.simulation 'racing-game.circuit
                           'racing-game.lap-timing-test)
                  {:pure (pure-call-checks)
                   :tests (test/run-tests 'racing-game.lap-timing-test)})
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
        result (assoc checked
                      :duration-ms duration-ms
                      :events (frequencies (map :event @events))
                      :artifact-events (filterv :artifact-key @events)
                      :compiled (filterv #(#{:compiled :compile-failed} (:event %)) @events)
                      :unmatched-bundle-hits unmatched
                      :unbundled-handlers unbundled
                      :new-libraries (filterv ours? writes)
                      :concurrent-cache-writes (filterv #(not (ours? %)) writes)
                      :output (str output))]
    (io/make-parents result-file)
    (spit result-file (artifact/print-data result))
    (assert (= 12 (get-in result [:pure :checks])))
    (assert (zero? (+ (get-in result [:tests :fail]) (get-in result [:tests :error])))
            (artifact/print-data (:tests result)))
    (assert (empty? (:compiled result)) (artifact/print-data (:compiled result)))
    (assert (seq hits))
    (assert (= 1 (get-in result [:events :bundle-loaded])))
    (assert (empty? unmatched) (artifact/print-data unmatched))
    (assert (empty? unbundled) (artifact/print-data unbundled))
    (assert (empty? (:new-libraries result)) (artifact/print-data (:new-libraries result)))
    result))
