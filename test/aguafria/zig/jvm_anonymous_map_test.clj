(ns aguafria.zig.jvm-anonymous-map-test
  (:require [aguafria.zig.discovery :as discovery]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest map-parameter-numbering-does-not-depend-on-insertion-order
  (let [inputs #'jvm/prepared-call-inputs
        first-map (array-map :int :u32 :float :f64 :b {:comptime true} :s {:comptime "hi"})
        second-map (array-map :s {:comptime "hi"} :b {:comptime true} :float :f64 :int :u32)
        first-inputs (inputs [{:map first-map}] [{:type :anytype}])
        second-inputs (inputs [{:map second-map}] [{:type :anytype}])]
    (is (= (select-keys first-inputs [:parameters :expression-arguments])
           (select-keys second-inputs [:parameters :expression-arguments]))))
  (let [refine #'discovery/refine-jvm-map-representations!]
    (with-redefs [runtime/inspect-module! (fn [& _] (throw (ex-info "Unexpected type query" {})))]
      (doseq [operation [{:status :unobserved :storage-kind :field :signatures [[nil [:*const nil]]]}
                         {:status :observed :storage-kind :field :method-call? true :signatures [[nil [:*const nil]]]}
                         {:status :observed :storage-kind :field :signatures [[nil [:*const nil]]]
                          :jvm-value-sources [{:type-source :existing-native-value-plan}]}]]
        (is (nil? (refine {:operations [operation]})))))))

(defn- anonymous-map-jvm [cache producer?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.keyword 'aguafria.zig.runtime
                    'aguafria.zig.precompile 'aguafria.zig.explain 'aguafria.zig.emitter
                    'aguafria.zig.value 'clojure.java.io 'clojure.java.shell 'clojure.string)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (binding [aguafria.zig.runtime/*source-only-registration?* true]
             (require 'aguafria.zig.discovery-anonymous-map-fixture))
           (if ~producer?
             (let [fail!# (fn [& _#] (throw (ex-info "Preparation invoked native code" {})))
                   report# (with-redefs [aguafria.zig.runtime/invoke! fail!#
                                         aguafria.zig.runtime/invoke-with-result! fail!#]
                             (aguafria.zig.precompile/precompile!
                              {:analyze ['aguafria.zig.discovery-anonymous-map-fixture]
                               :parallelism 1 :report-file ~(str cache "/report.edn")}))
                   analysis# (first (:analysis report#))
                   refinement# (:jvm-map-representation-refinement analysis#)
                   identity#
                   (aguafria.zig.runtime/inspect-module!
                    'aguafria.zig.discovery-anonymous-map-fixture
                    (fn [declarations#]
                      (let [source# (aguafria.zig.emitter/emit-module
                                     "aguafria.zig.discovery-anonymous-map-fixture" declarations#)
                            receiver# (second (re-find #"const ([^ ]+) = __aguafria_binding_[0-9]+;" source#))
                            literal# (second (re-find #"try check\((.+)\);" source#))
                            line# (first (filter #(clojure.string/includes? % (str "const " receiver# " = "))
                                                 (clojure.string/split-lines source#)))]
                        (when-not (and receiver# literal# line#)
                          (throw (ex-info "Missing original anonymous specialization" {})))
                        {:source (clojure.string/replace
                                  source# line#
                                  (str line# "\n        comptime { if (@TypeOf(" receiver#
                                       ") != @TypeOf(" literal#
                                       ")) @compileError(\"different anonymous identity\"); }"))})))]
               (prn {:bundles (:bundles report#) :coverage (:coverage report#)
                     :operations (filterv #(contains? (:sources refinement#) (:id %)) (:operations analysis#))
                     :refinement refinement#
                     :repeated-literal-identity (select-keys identity# [:exit :err :source-path :command])}))
             (let [events# (atom []) commands# (atom []) original-shell# clojure.java.shell/sh
                   forms# (with-open [reader# (java.io.PushbackReader.
                                              (clojure.java.io/reader
                                               (clojure.java.io/resource
                                                "aguafria/zig/discovery_anonymous_map_fixture.clj")))]
                            (binding [*read-eval* false]
                              (into [] (take-while some?) (repeatedly #(read {:eof nil} reader#)))))
                   body# (first (filter #(and (seq? %) (= (symbol "fully-anonymous-struct") (second %))) forms#))
                   _# (when-not body# (throw (ex-info "Missing authored anonymous fixture body" {})))
                   outputs#
                   (with-redefs [clojure.java.shell/sh
                                 (fn [& command#]
                                   (swap! commands# conj (vec command#))
                                   (apply original-shell# command#))]
                     (binding [*ns* (the-ns 'aguafria.zig.discovery-anonymous-map-fixture)
                               aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                       (let [body-result# (aguafria.zig/value (eval (cons (symbol "do") (drop 2 body#))))
                             direct#
                             (eval '~'(let [original {:int (k/as 1234 :u32)
                                                     :float (k/as 12.34 :f64)
                                                     :b true :s "hi"}
                                            changed {:int (k/as 4321 :u32)
                                                     :float (k/as 43.21 :f64)
                                                     :b false :s (replacement-string)}]
                                        (mapv (fn [receiver]
                                                (mapv (fn [member]
                                                        (let [field (a/field receiver member)
                                                              decoded (a/value field)]
                                                          (try
                                                            (if (aguafria.zig.value/zig-pointer? decoded)
                                                              (String. (.toArray (aguafria.zig.value/pointer-segment decoded 2)
                                                                                java.lang.foreign.ValueLayout/JAVA_BYTE)
                                                                       java.nio.charset.StandardCharsets/UTF_8)
                                                              decoded)
                                                            (finally (java.lang.ref.Reference/reachabilityFence field)))))
                                                      [:int :float :b :s]))
                                              [original changed (into (array-map) (reverse (vec changed)))])))]
                         {:body body-result# :direct direct#})))]
               (prn (assoc outputs# :events @events# :commands @commands#))))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED" "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result)) (throw (ex-info "Anonymous map JVM failed" result)))
    (edn/read-string (:out result))))

(deftest compiler-reflected-field-maps-reuse-the-exact-producer-pack
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "anonymous-map-" (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (anonymous-map-jvm cache true)
        _ (spit (io/file cache "producer.edn") (pr-str producer))
        consumer (anonymous-map-jvm cache false)
        _ (spit (io/file cache "consumer.edn") (pr-str consumer))
        operations (:operations producer)
        refinement (:refinement producer)
        events (:events consumer)
        hits (filter #(= :bundle-cache-hit (:event %)) events)
        loads (filter #(= :bundle-loaded (:event %)) events)
        misses (filter #(or (#{:compiled :compile-failed} (:event %))
                            (and (= :disk-cache-hit (:event %)) (nil? (:bundle-id %)))) events)]
    (is (= 4 (count operations)))
    (is (= #{:int :float :b :s} (set (map :member operations))))
    (is (every? #(= [[nil [:*const nil]]] (:signatures %)) operations))
    (is (every? #(= :u32 (get-in % [:jvm-signatures 0 0 :map :int])) operations))
    (is (every? #(= :f64 (get-in % [:jvm-signatures 0 0 :map :float])) operations))
    (is (every? #(= #{[:*const [:array 2 {:sentinel 0} :u8]] {:comptime "hi"} [:slice-const :u8]}
                    (set (get-in % [:jvm-signatures 0 0 :map :s :representations]))) operations))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers operations)) (pr-str operations))
    (is (= :zig-compiler (:basis refinement)))
    (is (= :ordinary-jvm-map (:representation refinement)))
    (is (false? (:nominal-equivalence? refinement)))
    (is (false? (:native-storage? refinement)))
    (is (false? (:compiler-errors? refinement)))
    (is (= 1 (get-in producer [:repeated-literal-identity :exit])))
    (is (str/includes? (get-in producer [:repeated-literal-identity :err]) "different anonymous identity"))
    (is (= {:ok nil} (:body consumer)))
    (is (= [[1234 12.34 true "hi"] [4321 43.21 false "yo"] [4321 43.21 false "yo"]]
           (:direct consumer)))
    (is (= 1 (count loads)) (pr-str events))
    (is (seq hits))
    (is (every? #(= (get-in producer [:bundles :packs 0 :id]) (:bundle-id %)) hits))
    (is (empty? misses) (pr-str misses))
    (is (every? #(and (= 4 (count %)) (= "version" (second %)) (= :dir (nth % 2)))
                (:commands consumer)) (pr-str (:commands consumer)))
    (is (<= (count (:commands consumer)) 1))))
