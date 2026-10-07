(ns aguafria.zig.scoped-loop-observation-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.emitter :as emitter]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest scoped-token-contracts-use-lexical-captures-not-iterator-spelling
  (let [context (the-ns 'aguafria.zig.scoped-loop-observation-test)]
    (doseq [token ['k/for 'k/while 'k/switch]]
      (is (true? (:aguafria/scoped? (meta (ns-resolve context token))))))
    (doseq [source ['(k/for [item items] (k/+= sum item))
                    '(inline-for [item items] (k/+= sum item))
                    '(for-loop {} [item items] (k/+= sum item))]]
      (binding [emitter/*lexical-bindings* '#{item items sum}]
        (let [form (emitter/qualify-form context source)]
          (is (= source (:aguafria/scoped-template (meta form))))
          (is (= '[items sum] (mapv first (:aguafria/scoped-captures (meta form))))))))
    (binding [*ns* context]
      (let [expanded ((var k/scoped-token-expansion)
                      '(k/for [item items] (k/+= sum item))
                      '{item nil items nil sum nil} false)
            capture-map (nth expanded 3)]
        (is (= '(clojure.core/hash-map (quote items) items (quote sum) sum) capture-map))))))

(deftest observation-routes-native-loop-contracts-without-changing-source
  (let [context (the-ns 'aguafria.zig.scoped-loop-observation-test)]
    (doseq [[source statement? result?]
            [['(k/for [item items] (k/+= sum item)) true false]
             ['(k/while flag (k/+= sum 1)) true false]
             ['(k/for [item items] (k/+= sum item) (a/else-expression sum)) false true]
             ['(k/switch selector (case [0] sum) (case-else sum)) false true]]]
      (let [form (binding [emitter/*lexical-bindings* '#{items sum flag selector}]
                   (emitter/qualify-form context source))
            emit #(if statement? (emitter/emit-stmt-in context form) (emitter/emit-expr context form))
            ordinary (emit)
            observations (atom [])
            inspected (binding [emitter/*expression-observer*
                                (fn [observation]
                                  (swap! observations conj observation)
                                  (:source observation))]
                        (emit))
            scoped (filter #(get-in % [:var-meta :aguafria/scoped?]) @observations)]
        (is (= ordinary inspected))
        (is (= 1 (count scoped)))
        (is (= result? (emitter/scoped-result? context (:form (first scoped)))))
        (is (= source (:aguafria/scoped-template (meta (:form (first scoped))))))
        (when statement?
          (is (= :statement (:placement (first scoped))))
          (is (fn? (:place-probe (first scoped)))))))))

(defn- for-lesson-jvm [cache producer?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.precompile
                    'aguafria.zig.explain 'clojure.java.io 'clojure.java.shell)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (if ~producer?
             (let [fail!# (fn [& _#] (throw (ex-info "Preparation invoked native code" {})))
                   report# (with-redefs [aguafria.zig.runtime/invoke! fail!#
                                         aguafria.zig.runtime/invoke-with-result! fail!#]
                             (aguafria.zig.precompile/precompile!
                              {:analyze ['learn.example.test-for] :parallelism 1
                               :report-file ~(str cache "/report.edn")}))]
               (prn {:coverage (:coverage report#) :bundles (:bundles report#)
                     :loop-operations
                     (mapv #(select-keys % [:id :function :form :scope-result? :handlers])
                           (filter #(= 'aguafria.keyword/for (:function %))
                                   (get-in report# [:analysis 0 :operations])))}))
             (let [events# (atom []) commands# (atom []) original-shell# clojure.java.shell/sh
                   results#
                   (with-redefs [clojure.java.shell/sh
                                 (fn [& args#]
                                   (swap! commands# conj (vec args#))
                                   (apply original-shell# args#))]
                     (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                       (require 'learn.example.test-for)
                       (let [forms# (with-open [reader# (java.io.PushbackReader.
                                                        (clojure.java.io/reader
                                                         (clojure.java.io/resource "learn/example/test_for.clj")))]
                                      (binding [*read-eval* false]
                                        (into [] (take-while some?)
                                              (repeatedly #(read {:eof nil} reader#)))))
                             bodies# (filter #(and (seq? %) (= (symbol "a/deftest") (first %))) forms#)]
                         (binding [*ns* (the-ns 'learn.example.test-for)]
                           (mapv (fn [form#]
                                   {:name (second form#)
                                    :result (aguafria.zig/value
                                             (eval (cons (symbol "do") (drop 2 form#))))})
                                 bodies#)))))]
               (prn {:results results# :events @events# :commands @commands#})))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED" "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result)) (throw (ex-info "Scoped for lesson JVM failed" result)))
    (edn/read-string (:out result))))

(deftest actual-for-statement-and-value-bodies-share-the-producer-pack
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "scoped-for-" (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (for-lesson-jvm cache true)
        _ (spit (io/file cache "producer.edn") (pr-str producer))
        consumer (for-lesson-jvm cache false)
        _ (spit (io/file cache "consumer.edn") (pr-str consumer))
        loops (:loop-operations producer)
        events (:events consumer)
        hits (filter #(= :bundle-cache-hit (:event %)) events)
        misses (filter #(or (#{:compiled :compile-failed} (:event %))
                            (and (= :disk-cache-hit (:event %)) (nil? (:bundle-id %))
                                 (str/starts-with? (:module %) "aguafria.jvm."))) events)]
    (is (= 0 (get-in producer [:coverage :namespaces :baseline-failures])))
    (is (= 7 (count loops)))
    (is (= {false 6 true 1} (frequencies (map :scope-result? loops))))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers loops)) (pr-str loops))
    (is (= '[for-basics multi-object-for for-reference for-else] (mapv :name (:results consumer))))
    (is (every? #(= {:ok nil} (:result %)) (:results consumer)) (pr-str (:results consumer)))
    (is (empty? misses) (pr-str misses))
    (is (seq hits))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))))
    (is (every? #(= (get-in producer [:bundles :packs 0 :id]) (:bundle-id %)) hits))
    (is (every? #(and (= 4 (count %)) (= "version" (second %)) (= :dir (nth % 2)))
                (:commands consumer)) (pr-str (:commands consumer)))
    (is (<= (count (:commands consumer)) 1))))
