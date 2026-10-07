(ns aguafria.zig.jvm-map-phase-test
  (:require [aguafria.zig.discovery :as discovery]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest nested-map-strings-preserve-the-parameter-phase
  (let [schema #'discovery/operand-schema
        render pr-str
        source {:nested {:name "foo"}}
        runtime (schema render (render source) {:type :anytype} source "argumentSchema")
        comptime (schema render (render source)
                         {:type :anytype :properties {:zig/prefix "comptime"}}
                         source "argumentSchema")]
    (is (str/includes? runtime "jvmMapStringArgumentSchema"))
    (is (not (str/includes? comptime "jvmMapStringArgumentSchema")))
    (is (str/includes? comptime "comptimeValue"))))

(defn- phase-jvm [cache producer?]
  (let [code
        `(do
           (require 'aguafria.zig.runtime 'aguafria.zig.precompile
                    'aguafria.zig.explain 'aguafria.zig.value 'clojure.java.shell)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (binding [aguafria.zig.runtime/*source-only-registration?* true]
             (require 'aguafria.zig.jvm-map-phase-fixture))
           (if ~producer?
             (let [fail# (fn [& _#] (throw (ex-info "Producer invoked native code" {})))]
               (with-redefs [aguafria.zig.runtime/invoke! fail#
                             aguafria.zig.runtime/invoke-with-result! fail#]
                 (let [report# (aguafria.zig.precompile/precompile!
                                 {:analyze ['aguafria.zig.jvm-map-phase-fixture]
                                  :parallelism 1 :report-file ~(str cache "/report.edn")})]
                   (prn {:coverage (:coverage report#) :bundles (:bundles report#)
                         :operations (get-in report# [:analysis 0 :operations])}))))
             (let [events# (atom []) processes# (atom [])
                   original-sh# clojure.java.shell/sh
                   call# (fn []
                           (mapv (fn [function#]
                                   (let [result# ((resolve function#) {:name "foo"})]
                                     (if (aguafria.zig.value/zig-value? result#)
                                       (aguafria.zig.value/decoded result#)
                                       result#)))
                                 '[aguafria.zig.jvm-map-phase-fixture/comptime-name-length
                                   aguafria.zig.jvm-map-phase-fixture/runtime-name-length]))
                   result#
                   (with-redefs [clojure.java.shell/sh
                                 (fn [& args#]
                                   (swap! processes# conj (vec args#))
                                   (apply original-sh# args#))]
                     (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                       (let [first# (call#) cold# (count @processes#) second# (call#)]
                         {:first first# :second second#
                          :warm-process-count (- (count @processes#) cold#)})))]
               (prn (assoc result# :events @events# :processes @processes#))))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (spit (str cache (if producer? "/producer-process.edn" "/consumer-process.edn"))
          (pr-str result))
    (when-not (zero? (:exit result))
      (throw (ex-info "Map phase fixture JVM failed" {:cache cache :result result})))
    (let [data (edn/read-string (:out result))]
      (spit (str cache (if producer? "/producer.edn" "/consumer.edn")) (pr-str data))
      data)))

(deftest compiler-confirms-constant-map-phase-with-fresh-jvm-parity
  (let [cache (str (java.nio.file.Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "map-phase-" (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (phase-jvm cache true)
        export (first (filter #(= 'aguafria.keyword/export (:function %)) (:operations producer)))
        options (get-in export [:signatures 0 1 :map])
        runtime-call (first (filter #(= 'aguafria.zig.jvm-map-phase-fixture/runtime-name-length
                                       (:function %))
                                    (:operations producer)))
        runtime-strings (get-in runtime-call [:signatures 0 0 :map :name :representations])
        consumer (phase-jvm cache false)
        events (:events consumer)
        hits (filter #(= :bundle-cache-hit (:event %)) events)]
    (is (= {:comptime "aguafria_map_phase_fixture_export"} (:name options)))
    (is (= 1 (count (:handlers export))))
    (is (every? #(= :prepared (:status %)) (:handlers export)))
    (is (some #{[:slice-const :u8]} runtime-strings))
    (is (some #{{:comptime "foo"}} runtime-strings))
    (is (zero? (get-in producer [:coverage :runtime-candidates :not-fully-prepared])))
    (is (= [3 3] (:first consumer) (:second consumer)))
    (is (seq hits))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))))
    (is (every? #(= (get-in producer [:bundles :packs 0 :id]) (:bundle-id %)) hits))
    (is (not-any? #(or (#{:compiled :compile-failed} (:event %))
                      (and (= :disk-cache-hit (:event %)) (nil? (:bundle-id %)))) events))
    (is (zero? (:warm-process-count consumer)))
    (is (<= (count (:processes consumer)) 1))
    (is (every? #(and (= "version" (second %))
                     (or (= 2 (count %))
                         (and (= 4 (count %)) (= :dir (nth % 2)))))
                (:processes consumer)))))
