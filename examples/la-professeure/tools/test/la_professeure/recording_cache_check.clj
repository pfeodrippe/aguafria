(ns la-professeure.recording-cache-check
  "Fresh-JVM acceptance for recording-tool's compile-only AOT report."
  (:require [aguafria.zig :as a]
            [aguafria.zig.artifact :as artifact]
            [aguafria.zig.explain :as explain]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.value :as value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io StringWriter]
           [java.nio.file Files FileVisitResult SimpleFileVisitor]))

(defn- native-libraries [cache]
  ;; Native images live at cache/namespace/hash or cache/bundles/hash.
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

(defn- expected-text-hash [text]
  (reduce (fn [hash byte]
            (mod (*' (.xor (biginteger hash) (biginteger (bit-and 255 byte)))
                     1099511628211N)
                 18446744073709551616N))
          14695981039346656037N
          (.getBytes ^String text "UTF-8")))

(defn check!
  "Normally require recording-tool and check actual AOT keys and library reuse.
  Run in a fresh JVM after preparing recording-tool, without opening the game."
  [report-file]
  (let [verification-started (System/nanoTime)
        report (edn/read-string (slurp report-file))
        cache (:cache-dir report)
        prepared-entries
        (into #{} (mapcat (fn [{:keys [id]}]
                            (keys (:entries
                                   (edn/read-string
                                    (slurp (io/file cache "bundles" id "manifest.edn")))))))
              (get-in report [:bundles :packs]))
        schemas (->> (get-in report [:analysis 0 :operations])
                     (filter #(= 'aguafria.zig/type (:function %)))
                     (mapcat :signatures)
                     (map (comp :comptime-type first))
                     distinct vec)
        events (atom [])
        output (StringWriter.)
        before (native-libraries cache)
        started (System/nanoTime)]
    (assert (= cache (:cache-dir (a/configuration)))
            "Use the same cache directory for preparation and ordinary invocation")
    (assert (seq prepared-entries) "Preparation must have produced an AOT pack")
    (assert (seq schemas) "Preparation must contain compiler-observed type schemas")
    (let [result
          (binding [*out* output *err* output
                    explain/*reporter* #(swap! events conj %)]
            (require 'la-professeure.recording-tool)
            (let [names (mapv (fn [schema]
                                (let [type (a/type schema)]
                                  (assert (a/zig-type? type))
                                  (assert (= (jvm/constructor-type schema)
                                             (jvm/constructor-type type)))
                                  (:zig-name (value/type-info type)))) schemas)
                  hash-fn (resolve 'la-professeure.recording-tool/text-hash)
                  hashes (mapv (fn [text]
                                 (with-open [hash (hash-fn text)]
                                   (let [result (a/value hash)]
                                     (assert (= (expected-text-hash text) result))
                                     result))) ["hello" "jello" "bonjour ☔"])]
              {:zig-names names :hashes hashes}))
          call-duration-ms (/ (- (System/nanoTime) started) 1e6)
          artifact-events (mapv #(select-keys % [:event :module :artifact-key :bundle-id])
                                (filter :artifact-key @events))
          hits (filterv #(= :bundle-cache-hit (:event %)) artifact-events)
          requested-keys (into #{} (mapcat #(remove nil? [(:artifact-key %) (:bundle-id %)]))
                               artifact-events)
          writes (vec (remove before (native-libraries cache)))
          requested-write? #(requested-keys (.getName (.getParentFile (io/file %))))
          unknown (filterv #(not (prepared-entries
                                  (artifact/key-for :bundle-entry
                                                    [(:module %) (:artifact-key %)]))) hits)
          result (assoc result
                        :duration-ms call-duration-ms
                        :verification-duration-ms (/ (- (System/nanoTime) verification-started) 1e6)
                        :events (frequencies (map :event @events))
                        :artifact-events artifact-events
                        :bundle-keys-match? (empty? unknown)
                        :new-libraries (filterv requested-write? writes)
                        :concurrent-cache-writes (filterv #(not (requested-write? %)) writes)
                        :output (str output))]
      (assert (zero? (get-in result [:events :compiled] 0)) (pr-str artifact-events))
      (assert (seq hits) "Ordinary calls must actually use the AOT bundle")
      (assert (= 1 (get-in result [:events :bundle-loaded])))
      (assert (empty? unknown) (pr-str unknown))
      (when (seq (:new-libraries result))
        (throw (ex-info "Ordinary AOT calls created native libraries" {:result result})))
      result)))
