(ns aguafria.zig.jvm-lexical-container-test
  (:require [aguafria.zig.discovery :as discovery]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest explicit-container-source-does-not-infer-runtime-captures
  (let [context (the-ns 'aguafria.zig.jvm-lexical-container-test)
        source #'discovery/jvm-anonymous-type-source
        closed (emitter/qualify-form context
                                     '(let [Point (aguafria.zig/struct [[:x :u8]])
                                            Item (aguafria.zig/union [[:point Point]])]
                                        Item))
        runtime-local (binding [emitter/*local-type-bindings* {'input false}]
                        (emitter/qualify-form context
                                             '(let [S (aguafria.zig/struct [[:x input]])] S)))]
    (is (= (ns-name context) (:caller (source context (last closed)))))
    (is (= 1 (count (:locals (source context (last closed))))))
    (is (nil? (source context (last runtime-local)))))
  (let [caller 'aguafria.zig.jvm-lexical-container-test
        container '(container {:kind :struct} [(field-decl :x {} :u8)])
        before (set (map ns-name (all-ns)))
        plan (jvm/anonymous-type-plan caller container {})]
    (is (= before (set (map ns-name (all-ns)))))
    (is (= plan (jvm/anonymous-type-plan caller container {})))
    (binding [runtime/*source-only-registration?* true]
      (is (= (:type plan)
             (jvm/constructor-type (jvm/anonymous-type! caller container {})))))))

(defn- lexical-container-jvm [cache prepare?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.keyword 'aguafria.zig.runtime
                    'aguafria.zig.precompile 'aguafria.zig.explain 'aguafria.zig.jvm)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (binding [aguafria.zig.runtime/*source-only-registration?* true]
             (require 'aguafria.zig.discovery-lexical-container-fixture))
           (if ~prepare?
             (with-redefs [aguafria.zig.runtime/invoke!
                           (fn [& _#] (throw (ex-info "Native body invoked during preparation" {})))
                           aguafria.zig.runtime/invoke-with-result!
                           (fn [& _#] (throw (ex-info "Native body invoked during preparation" {})))]
               (let [report# (aguafria.zig.precompile/precompile!
                              {:analyze ['aguafria.zig.discovery-lexical-container-fixture]
                               :report-file ~(str cache "/report.edn")})
                     analysis# (first (:analysis report#))]
                 (prn {:operations (filterv :jvm-signatures (:operations analysis#))
                       :refinement (:jvm-representation-refinement analysis#)
                       :bundles (:bundles report#)})))
             (let [events# (atom [])
                   outputs#
                   (binding [*ns* (the-ns 'aguafria.zig.discovery-lexical-container-fixture)
                             aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                     [(aguafria.zig/value (eval '~'(let [Foo (a/struct [])] (k/typeName Foo))))
                      (aguafria.zig/value (eval '~'(k/typeName (a/struct []))))
                      (aguafria.zig/value
                       (eval '~'(let [S (a/struct [[:a {:align 2} :u32]
                                                  [:b {:align 64} :u32]])]
                                  (k/alignOf S))))
                      (aguafria.zig/value
                       (eval '~'(let [S (a/struct [[:x {:var 1234} :i32]])]
                                  (k/+= (:x S) 1) (:x S))))
                      (aguafria.zig/value
                       (eval '~'(let [S (a/struct [[:x {:var 1234} :i32]])]
                                  (k/+= (:x S) 1) (:x S))))
                      (eval '~'(let [Point (a/struct [[:x :u8] [:y :u8]])
                                     Item (a/union {:attrs #{k/enum}}
                                                   [[:a :u32] [:c Point] [:d :void] [:e :u32]])]
                                 (mapv a/value [(:a Item) (:e Item) (:c Item) (:d Item)])))])]
               (prn {:outputs outputs# :events @events#})))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Lexical container JVM failed" result)))
    (edn/read-string (:out result))))

(deftest compiler-observed-jvm-containers-reuse-the-producer-pack
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "lexical-container-" (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (lexical-container-jvm cache true)
        consumer (lexical-container-jvm cache false)
        operations (:operations producer)
        events (:events consumer)
        packs (filter #(= :bundle-loaded (:event %)) events)
        misses (filter #(and (contains? #{:compiled :disk-cache-hit} (:event %))
                              (nil? (:bundle-id %))) events)
        [name inline-name alignment first-value second-value tags] (:outputs consumer)]
    (is (= 9 (count operations)))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers operations)) (pr-str operations))
    (is (every? #(= {:comptime-type nil} (get-in % [:signatures 0 0])) operations))
    (is (= :ordinary-jvm (get-in producer [:refinement :representation])))
    (is (false? (get-in producer [:refinement :nominal-equivalence?])))
    (is (false? (get-in producer [:refinement :compiler-errors?])))
    (is (every? #(= [[:* {:size :slice :const? true :sentinel 0} :u8]]
                    (:jvm-result-reader-types %))
                (filter #(= 'aguafria.keyword/typeName (:function %)) operations)))
    (is (= name inline-name))
    (is (= "aguafria.jvm.container-0f3f44df7c1b262c4f2374c4.Type" name))
    (is (str/ends-with? name ".Type"))
    (is (= [64 1235 1236 [:a :e :c :d]] [alignment first-value second-value tags]))
    (is (= 1 (count packs)) (pr-str events))
    (is (every? #(= (get-in producer [:bundles :packs 0 :id]) (:bundle-id %))
                (filter #(= :bundle-cache-hit (:event %)) events)))
    (is (str/includes? (:path (first packs))
                      (str "/bundles/" (get-in producer [:bundles :packs 0 :id]) "/")))
    (is (empty? misses) (pr-str misses))
    (is (empty? (filter #(= :compiled (:event %)) events)) (pr-str events))))
