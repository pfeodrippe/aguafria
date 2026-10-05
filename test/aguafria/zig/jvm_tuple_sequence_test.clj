(ns aguafria.zig.jvm-tuple-sequence-test
  (:require [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest adapter-only-result-readers-do-not-demand-authored-inspection-roots
  (let [readers (atom [])]
    (with-redefs [jvm/precompile-tuple-sequence!
                  (fn [& _] (throw (ex-info "Adapter-only reader queried tuple metadata" {})))
                  aguafria.zig.jvm/precompile-inspection!
                  (fn [context type] (swap! readers conj [(ns-name context) type]))]
      (jvm/precompile-result-reader! (str *ns*) :u32))
    (is (= [[(ns-name *ns*) :u32]] @readers))))

(deftest tuple-profile-emits-pointer-captures-in-the-caller-context
  (let [context (the-ns 'aguafria.zig.jvm-tuple-sequence-test)
        sources (atom [])
        message "aguafria.tuple-profile:0:nil"
        encoded (.formatHex (java.util.HexFormat/of)
                            (.getBytes message java.nio.charset.StandardCharsets/UTF_8))
        type '(aguafria.keyword/TypeOf
               (block
                (let [items (aguafria.keyword/var
                             (aguafria.zig/array [1 2] :u8))]
                  (aguafria.keyword/for
                   [(aguafria.keyword/* item) (aguafria.keyword/& items)]
                   (aguafria.keyword/= (deref item) 0)))))]
    (with-redefs [runtime/registered-declarations (constantly [])
                  runtime/inspect-module!
                  (fn [module supplier]
                    (swap! sources conj [module (:source (supplier []))])
                    {:exit 1
                     :err (str "Compile Log Output:\n\"aguafria.operation.hex:"
                               encoded "\"\n")})]
      (is (nil? (#'jvm/query-compiler-tuple-profile! context type))))
    (is (= [(str (ns-name context))] (mapv first @sources)))
    (is (str/includes? (second (first @sources)) "|*item|"))))

(deftest tuple-profile-cache-tracks-source-not-unrelated-adapter-growth
  (let [context *ns*
        calls (atom 0)
        source {:declaration-key [:fn (gensym "tuple-owner-")]
                :implementation-fingerprint :initial}
        declarations (atom [source])
        type (symbol (str (ns-name context)) (str (gensym "native-type-")))]
    (with-redefs [runtime/registered-declarations (fn [_] @declarations)
                  aguafria.zig.jvm/query-compiler-tuple-profile!
                  (fn [& _] (swap! calls inc) nil)]
      (is (nil? (#'jvm/compiler-tuple-profile! context type)))
      (swap! declarations conj {:declaration-key [:fn 'unrelated]
                                :name 'unrelated :jvm-adapter? true
                                :implementation-fingerprint :other})
      (is (nil? (#'jvm/compiler-tuple-profile! context type)))
      (is (= 1 @calls))
      (swap! declarations assoc-in [0 :implementation-fingerprint] :changed)
      (is (nil? (#'jvm/compiler-tuple-profile! context type)))
      (is (= 2 @calls))
      (doseq [non-struct-type [:u32 :void [:array 3 :u8] [:vector 3 :u8]]]
        (is (nil? (#'jvm/compiler-tuple-profile! context non-struct-type))))
      (is (= 2 @calls)))))

(defn- tuple-sequence-jvm [cache producer?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.precompile
                    'aguafria.zig.explain 'aguafria.zig.value 'clojure.java.io)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (if ~producer?
             (let [fail!# (fn [& _#] (throw (ex-info "Preparation invoked native code" {})))]
               (with-redefs [aguafria.zig.runtime/invoke! fail!#
                             aguafria.zig.runtime/invoke-with-result! fail!#]
                 (let [report# (aguafria.zig.precompile/precompile!
                                {:namespaces ['aguafria.zig.jvm-tuple-sequence-fixture]
                                 :analyze ['aguafria.zig.jvm-tuple-sequence-fixture]
                                 :parallelism 1})]
                   (prn {:bundles (:bundles report#) :coverage (:coverage report#)}))))
             (do
               (require 'aguafria.zig.jvm-tuple-sequence-fixture)
               (let [events# (atom [])
                     forms# (with-open [reader# (java.io.PushbackReader.
                                                (clojure.java.io/reader
                                                 (clojure.java.io/resource
                                                  "aguafria/zig/jvm_tuple_sequence_fixture.clj")))]
                              (binding [*read-eval* false]
                                (doall (take-while some?
                                                  (repeatedly #(read {:eof nil} reader#))))))
                     bodies# (into {} (for [form# forms#
                                            :when (and (seq? form#) (= "a/defn" (str (first form#))))]
                                        [(second form#) (cons (symbol "do") (drop 4 form#))]))
                     output# (java.io.StringWriter.)
                     results#
                     (binding [*ns* (the-ns 'aguafria.zig.jvm-tuple-sequence-fixture)
                               *err* output#
                               aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                       (let [body-results# (mapv #(aguafria.zig/value (eval (get bodies# %)))
                                                 (mapv symbol ["returned-body" "scoped-body"
                                                               "pointer-capture-body"]))]
                         (when (some #(and (map? %) (contains? % :error)) body-results#)
                           (throw (ex-info "Body returned a native error" {:results body-results#})))
                         ;; Check ownership after the actual bodies, without
                         ;; pre-running them to warm their adapters.
                         (with-open [tuple# ((find-var 'aguafria.zig.jvm-tuple-sequence-fixture/tuple-result) 10 3)]
                           (let [elements# (vec (seq tuple#))
                                 state# (aguafria.zig.value/realize! tuple#)
                                 elements-info#
                                 (mapv (fn [element#]
                                         {:native? (aguafria.zig.value/zig-value? element#)
                                          :type (aguafria.zig.value/qualified-type element#)
                                          :value (aguafria.zig/value element#)
                                          :retains-owner?
                                          (boolean (some #(identical? tuple# %)
                                                         (:owners (aguafria.zig.value/realize! element#))))})
                                       elements#)]
                             {:bodies body-results# :representation (:representation state#)
                              :elements elements-info#
                              :indexed-type (aguafria.zig.value/qualified-type (nth tuple# 0))
                              :default (nth tuple# 2 :missing)}))))]
                 (prn {:results results# :output (str output#) :events @events#}))))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Tuple sequence JVM failed" result)))
    (edn/read-string (:out result))))

(deftest fresh-native-tuples-retain-types-and-use-only-the-producer-pack
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "tuple-sequence-" (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (tuple-sequence-jvm cache true)
        consumer (tuple-sequence-jvm cache false)
        events (:events consumer)
        hits (filter #(= :bundle-cache-hit (:event %)) events)
        packs (filter #(= :bundle-loaded (:event %)) events)
        misses (filter #(or (contains? #{:compiled :compile-failed} (:event %))
                            (and (= :disk-cache-hit (:event %))
                                 (str/starts-with? (str (:module %)) "aguafria.jvm."))) events)]
    (spit (io/file cache "producer.edn") (pr-str producer))
    (spit (io/file cache "consumer.edn") (pr-str consumer))
    (is (= 0 (get-in producer [:coverage :namespaces :baseline-failures])))
    (is (= {:prepared 4} (get-in producer [:coverage :declared-functions :statuses])))
    (is (= "quotient = 3\nremainder = 1\nminimum = 0\nmaximum = 9\ndigit = 1\n"
           (:output consumer)))
    (is (= [nil nil nil] (get-in consumer [:results :bodies])))
    (is (= :native (get-in consumer [:results :representation])))
    (is (= [{:native? true :type :u32 :value 3 :retains-owner? true}
            {:native? true :type :u32 :value 1 :retains-owner? true}]
           (get-in consumer [:results :elements])))
    (is (= :u32 (get-in consumer [:results :indexed-type])))
    (is (= :missing (get-in consumer [:results :default])))
    (is (empty? misses) (pr-str misses))
    (is (seq hits))
    (is (= 1 (count packs)) (pr-str events))
    (is (every? #(= (get-in producer [:bundles :packs 0 :id]) (:bundle-id %)) hits))))
