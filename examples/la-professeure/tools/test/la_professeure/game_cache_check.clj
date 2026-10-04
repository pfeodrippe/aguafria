(ns la-professeure.game-cache-check
  "Fresh-JVM acceptance for ordinary game/Studio calls and their AOT artifacts."
  (:require [aguafria.zig :as a]
            [aguafria.zig.artifact :as artifact]
            [aguafria.zig.explain :as explain]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test])
  (:import [java.io StringWriter]
           [java.nio.file Files FileVisitResult SimpleFileVisitor]))

(defn- native-libraries [cache]
  (let [root (.toPath (io/file cache))
        libraries (atom #{})]
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
           (swap! libraries conj (str path)))
         FileVisitResult/CONTINUE)))
    @libraries))

(defn- unbundled-handlers [standalone events]
  (filterv #(and (= :disk-cache-hit (:event %))
                 (str/starts-with? (or (:module %) "") "aguafria.jvm.")
                 (not (standalone (artifact/key-for :bundle-entry
                                                    [(:module %) (:artifact-key %)]))))
           events))

(defn check!
  "Require the game normally and run its safe JVM checks after preparation.
  report-files identifies the prepared manifests; result-file retains failures
  as well as successes. This checker must run in a fresh, ordinary JVM."
  [report-files result-file]
  (assert (nil? (find-ns 'la-professeure.jvm-preparation-test))
          "Run the cache checker before loading the game test namespace")
  (let [reports (mapv #(edn/read-string (slurp %)) report-files)
        cache (:cache-dir (first reports))
        _ (assert (every? #(= cache (:cache-dir %)) reports))
        _ (assert (= cache (:cache-dir (a/configuration))))
        entries (into #{}
                      (mapcat (fn [{:keys [id]}]
                                (keys (:entries
                                       (edn/read-string
                                        (slurp (io/file cache "bundles" id "manifest.edn")))))))
                      (mapcat #(get-in % [:bundles :packs]) reports))
        _ (assert (seq entries) "Preparation must have published AOT artifacts")
        standalone (into #{} (keep :artifact-id)
                         (mapcat #(get-in % [:bundles :standalone]) reports))
        events (atom [])
        output (StringWriter.)
        before (native-libraries cache)
        started (System/nanoTime)
        tests (binding [*out* output *err* output test/*test-out* output
                        explain/*reporter* #(swap! events conj %)]
                (require 'la-professeure.jvm-preparation-test)
                (test/run-tests 'la-professeure.jvm-preparation-test))
        duration-ms (/ (- (System/nanoTime) started) 1e6)
        hits (filterv #(= :bundle-cache-hit (:event %)) @events)
        unmatched (filterv #(not (entries (artifact/key-for :bundle-entry
                                                            [(:module %) (:artifact-key %)])))
                           hits)
        unbundled (unbundled-handlers standalone @events)
        requested-keys (into #{} (mapcat #(remove nil? [(:artifact-key %) (:bundle-id %)]))
                             @events)
        writes (vec (remove before (native-libraries cache)))
        requested-write? #(requested-keys (.getName (.getParentFile (io/file %))))
        result (assoc tests
                      :duration-ms duration-ms
                      :events (frequencies (map :event @events))
                      :artifact-events (filterv :artifact-key @events)
                      :compiled (filterv #(#{:compiled :compile-failed} (:event %)) @events)
                      :bundle-hit-keys-match? (empty? unmatched)
                      :unmatched-bundle-hits unmatched
                      :unbundled-handlers unbundled
                      :new-libraries (filterv requested-write? writes)
                      :concurrent-cache-writes (filterv #(not (requested-write? %)) writes)
                      :output (str output))]
    (io/make-parents result-file)
    (spit result-file (pr-str result))
    (assert (zero? (+ (:fail tests) (:error tests))) (pr-str tests))
    (assert (empty? (:compiled result)) (pr-str (:compiled result)))
    (assert (seq hits) "Ordinary calls must actually use AOT")
    (assert (empty? unmatched) (pr-str unmatched))
    (assert (empty? unbundled)
            (str "Generated handlers fell back to standalone artifacts: " (pr-str unbundled)))
    (assert (empty? (:new-libraries result)) (pr-str (:new-libraries result)))
    result))
