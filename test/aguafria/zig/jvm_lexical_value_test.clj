(ns aguafria.zig.jvm-lexical-value-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest explicit-value-and-capture-provenance-does-not-guess-types
  (let [context (the-ns 'aguafria.zig.jvm-lexical-value-test)
        source #'discovery/jvm-value-type-source
        qualified (emitter/qualify-form
                   context
                   '(let [Point (a/struct [[:x :u8]])
                          Item (a/union {:attrs #{k/enum}} [[:c Point]])
                          immutable (Point {:x 1})
                          value (k/var (Item {:c immutable}))]
                      (:x immutable)
                      (:c value)
                      (k/switch value
                                (case [(:c Item)] [(a/pointer-capture item)]
                                      (:x @item)))))
        immutable (source context (second (nth qualified 2)))
        mutable (source context (second (nth qualified 3)))
        pointer (first (filter #(and (seq? %) (= 'deref (first %)))
                               (tree-seq coll? seq (last qualified))))
        captured (source context (second pointer))
        dereferenced (source context pointer)]
    (is (false? (:mutable? immutable)))
    (is (true? (:mutable? mutable)))
    (is (= [:c] (:capture-fields captured)))
    (is (true? (:pointer? captured)))
    (is (false? (:mutable? captured)))
    (is (true? (:pointer-mutable? captured)))
    (is (false? (:pointer? dereferenced)))
    (is (true? (:mutable? dereferenced)))
    (is (= (:type-source mutable) (:type-source captured)))
    (is (nil? (source context 'unbound-runtime-input))))
  (let [context (the-ns 'aguafria.zig.jvm-lexical-value-test)
        source #'discovery/jvm-value-type-source
        qualified (binding [emitter/*local-type-bindings* {'input false}]
                    (emitter/qualify-form
                     context '(let [S (a/struct [[:x input]]) value (S {:x 1})] (:x value))))]
    (is (nil? (source context (second (last qualified)))))))

(deftest construction-parameters-use-the-shared-native-qualifier
  (let [caller 'aguafria.zig.jvm-lexical-value-test
        plan (jvm/anonymous-type-plan
              caller '(container {:kind :struct} [(field-decl :x {} :u8)]) {})]
    (binding [runtime/*source-only-registration?* true]
      (jvm/register-anonymous-type-plan! plan)
      (let [plain (:type plan)
            qualified (jvm/constructor-type plain)
            inputs (fn [type] {:expression-arguments [{:x 'input_0}]
                              :parameters [{:name 'input_0 :type type}]})
            prepare #'jvm/prepare-construction!]
        (is (:aguafria/zig-reference (meta qualified)))
        (is (= (prepare plain (inputs plain))
               (prepare plain (inputs qualified))))))))

(defn- lexical-value-jvm [cache prepare?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.precompile
                    'aguafria.zig.explain 'clojure.java.io)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (binding [aguafria.zig.runtime/*source-only-registration?* true]
             (require 'aguafria.zig.discovery-lexical-value-fixture))
           (if ~prepare?
             (with-redefs [aguafria.zig.runtime/invoke!
                           (fn [& _#] (throw (ex-info "Native body invoked during preparation" {})))
                           aguafria.zig.runtime/invoke-with-result!
                           (fn [& _#] (throw (ex-info "Native body invoked during preparation" {})))]
               (let [report# (aguafria.zig.precompile/precompile!
                              {:analyze ['aguafria.zig.discovery-lexical-value-fixture]
                               :report-file ~(str cache "/report.edn")})
                     analysis# (first (:analysis report#))
                     refinement# (:jvm-value-representation-refinement analysis#)]
                 (prn {:operations (filterv #(contains? (:sources refinement#) (:id %))
                                             (:operations analysis#))
                       :refinement refinement#
                       :construction-inputs (:jvm-construction-input-refinement analysis#)
                       :bundles (:bundles report#)})))
             (let [events# (atom [])
                   forms# (with-open [reader# (java.io.PushbackReader.
                                              (clojure.java.io/reader
                                               (clojure.java.io/resource
                                                "aguafria/zig/discovery_lexical_value_fixture.clj")))]
                            (binding [*read-eval* false]
                              (into [] (take-while some?)
                                    (repeatedly #(read {:eof nil} reader#)))))
                   bodies# (into []
                                 (mapcat (fn [[_# _# & body#]]
                                           (drop-while #(or (string? %) (map? %)) body#)))
                                 (filter #(and (seq? %) (symbol? (first %))
                                               (= "deftest" (name (first %)))) forms#))
                   outputs# (binding [*ns* (the-ns 'aguafria.zig.discovery-lexical-value-fixture)
                                      aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                              (mapv #(aguafria.zig/value (eval %)) bodies#))
                   direct-outputs#
                   (binding [*ns* (the-ns 'aguafria.zig.discovery-lexical-value-fixture)
                             aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                     ;; The enclosing native switch does not demand its inner
                     ;; JVM adapters. Exercise their readers/storage directly.
                     (eval '~'(let [Point (a/struct [[:x :u8] [:y :u8]])
                                    Item (a/union {:attrs #{k/enum}}
                                                  [[:a :u32] [:c Point] [:d :void] [:e :u32]])
                                    value (k/var (Item {:c (Point {:x 1 :y 2})}))
                                    item (k/& (:c value))]
                                [(a/value @item)
                                 (a/value (:x @item))
                                 (a/value (a/with-block :blk
                                            (k/+= (:x @item) 1)
                                            (k/break :blk 6)))
                                 (a/value (a/get-in value [:c :x]))])))]
               (prn {:outputs outputs# :direct-outputs direct-outputs# :events @events#})))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Lexical value JVM failed" result)))
    (edn/read-string (:out result))))

(deftest compiler-owned-value-dependencies-reuse-the-producer-pack
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "lexical-value-" (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (lexical-value-jvm cache true)
        consumer (lexical-value-jvm cache false)
        operations (:operations producer)
        events (:events consumer)
        packs (filter #(= :bundle-loaded (:event %)) events)
        misses (filter #(and (contains? #{:compiled :disk-cache-hit} (:event %))
                              (nil? (:bundle-id %))) events)
        construction (:construction-inputs producer)]
    (is (= 11 (count operations)))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers operations)) (pr-str operations))
    (is (every? #(some nil? (tree-seq coll? seq (:signatures %))) operations))
    (is (= :zig-compiler (get-in producer [:refinement :basis])))
    (is (= :ordinary-jvm (get-in producer [:refinement :representation])))
    (is (false? (get-in producer [:refinement :nominal-equivalence?])))
    (is (false? (get-in producer [:refinement :compiler-errors?])))
    (is (false? (:compiler-errors? construction)))
    (is (= 2 (count (:dependencies construction))))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers (:dependencies construction))))
    (is (= (repeat 5 {:ok nil}) (:outputs consumer)))
    (is (= [{:x 1 :y 2} 1 6 2] (:direct-outputs consumer)))
    (is (= 1 (count packs)) (pr-str events))
    (is (seq (filter #(= :bundle-cache-hit (:event %)) events)))
    (is (every? #(= (get-in producer [:bundles :packs 0 :id]) (:bundle-id %))
                (filter #(= :bundle-cache-hit (:event %)) events)))
    (is (str/includes? (:path (first packs))
                      (str "/bundles/" (get-in producer [:bundles :packs 0 :id]) "/")))
    (is (empty? misses) (pr-str misses))
    (is (empty? (filter #(= :compiled (:event %)) events)) (pr-str events))))
