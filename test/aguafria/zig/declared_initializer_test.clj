(ns aguafria.zig.declared-initializer-test
  (:require [aguafria.zig.discovery :as discovery]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.precompile :as precompile]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest initializer-owner-markers-do-not-change-emitted-zig
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.declared-initializer-fixture))
  (let [module "aguafria.zig.declared-initializer-fixture"
        declarations (runtime/registered-declarations module)
        ordinary (emitter/emit-module module declarations)
        observations (atom [])
        inspected
        (binding [emitter/*expression-observer*
                  (fn [expression]
                    (swap! observations conj
                           (select-keys expression [:form :declaration-name
                                                    :declared-constant-initializer?]))
                    (:source expression))]
          (emitter/emit-module module declarations))
        top-names #{'first-result 'second-result 'nested-result
                    'inferred-array 'typed-array 'element-type
                    'typed-number 'maybe-number 'no-number 'pair 'alias-pointer}]
    (is (= ordinary inspected))
    (is (= top-names
           (into #{} (comp (filter :declared-constant-initializer?)
                           (map :declaration-name) (filter top-names)) @observations)))
    (is (some #(and (= 'nested-result (:declaration-name %))
                    (not (:declared-constant-initializer? %))) @observations))
    (let [members (filter #(= 'Container (:declaration-name %)) @observations)]
      (is (seq members) "The container-member exclusion must not pass vacuously")
      (is (not-any? :declared-constant-initializer? members)))))

(deftest decoding-uses-compiler-reported-storage-not-the-declaration-expression
  (with-open [arena (java.lang.foreign.Arena/ofConfined)]
    (let [storage (.allocate arena 4 4)
          native (value/native-value
                  {:type '(aguafria.keyword/TypeOf fixture/original)}
                  (constantly {:representation :native :segment storage
                               :native-type [:array 1 :i32]}))
          query (atom nil)]
      (with-redefs-fn {#'jvm/invoke-expression!
                      (fn [_ expression & _]
                        (reset! query expression)
                        [9])}
        #(is (= [9] (value/decoded native))))
      (is (some #{[:array 1 :i32]} (tree-seq coll? seq @query)))
      (is (not-any? #{'(aguafria.keyword/TypeOf fixture/original)}
                    (tree-seq coll? seq @query))))))

(deftest declared-initializer-plans-keep-independent-call-failures-visible
  (let [constant 'fixture.owner/result
        operation {:status :observed :root-declaration-name 'result
                   :declared-constant-initializer? true
                   :handlers [{:status :failed :message "runtime remainder rejected"}]}
        reader {:constant constant :status :prepared}
        prepared (#'discovery/retain-declared-initializer-owner
                  "fixture.owner" {constant reader} operation)]
    (is (= :comptime (:enclosing-context prepared)))
    (is (= :declared-constant (:execution-plan prepared)))
    (is (false? (:independent-call? prepared)))
    (is (= (:handlers operation) (:independent-call-handlers prepared)))
    (is (= [{:constant constant :status :prepared :execution-context :comptime}]
           (:handlers prepared)))
    (is (= {:total 1 :owner-handlers {:prepared 1}
            :independent-call-handlers {:failed 1}}
           (:declared-initializer-operations (precompile/coverage [{:operations [prepared]}]))))
    (let [unobserved (assoc operation :status :unobserved)]
      (is (= unobserved (#'discovery/retain-declared-initializer-owner
                         "fixture.owner" {} unobserved))
          "Unobserved source must not gain a compiler-verified owner"))
    (is (= :failed (get-in (#'discovery/retain-declared-initializer-owner
                            "fixture.owner" {} operation) [:handlers 0 :status])))))

(defn- fresh-jvm [cache producer?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.explain)
           (aguafria.zig/configure! {:cache-dir ~cache})
           (if ~producer?
             (let [fail!# (fn [& _#] (throw (ex-info "Preparation invoked native code" {})))
                   report# (with-redefs [aguafria.zig.runtime/invoke! fail!#
                                         aguafria.zig.runtime/invoke-with-result! fail!#]
                             (aguafria.zig/precompile!
                              {:analyze ['aguafria.zig.declared-initializer-fixture]
                               :parallelism 1 :report-file ~(str cache "/report.edn")}))]
               (prn (select-keys report# [:analysis :coverage :bundles])))
             (let [events# (atom [])
                   results#
                   (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                     (require 'aguafria.zig.declared-initializer-fixture)
                     (mapv #(aguafria.zig/value (var-get (requiring-resolve %)))
                           ['aguafria.zig.declared-initializer-fixture/first-result
                            'aguafria.zig.declared-initializer-fixture/second-result
                            'aguafria.zig.declared-initializer-fixture/nested-result
                            'aguafria.zig.declared-initializer-fixture/inferred-array
                             'aguafria.zig.declared-initializer-fixture/typed-array
                             'aguafria.zig.declared-initializer-fixture/typed-number
                             'aguafria.zig.declared-initializer-fixture/maybe-number
                             'aguafria.zig.declared-initializer-fixture/no-number
                             'aguafria.zig.declared-initializer-fixture/pair]))
                   original# (var-get (requiring-resolve
                                      'aguafria.zig.declared-initializer-fixture/inferred-array))
                   alias# (var-get (requiring-resolve
                                   'aguafria.zig.declared-initializer-fixture/alias-pointer))
                   same-address?#
                   (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                     (= (.address (aguafria.zig.value/segment original#))
                        (aguafria.zig.value/pointer-address (aguafria.zig/value alias#))))]
               (prn {:results results# :same-address? same-address?# :events @events#})))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED" "-cp"
                         (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Fresh declared-initializer JVM failed" result)))
    (edn/read-string (:out result))))

(deftest compiler-owned-constant-initializers-reuse-their-real-reader-after-restart
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "declared-initializer-" (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (fresh-jvm cache true)
        _ (spit (io/file cache "producer.edn") (pr-str producer))
        consumer (fresh-jvm cache false)
        _ (spit (io/file cache "consumer.edn") (pr-str consumer))
        operations (get-in producer [:analysis 0 :operations])
        owned (filter #(= :declared-constant (:execution-plan %)) operations)
        events (:events consumer)
        hits (filter #(= :bundle-cache-hit (:event %)) events)]
    (is (= 11 (count owned)))
    (is (= #{'first-result 'second-result 'nested-result 'inferred-array 'typed-array 'element-type
             'typed-number 'maybe-number 'no-number 'pair 'alias-pointer}
           (set (map :declaration-name owned))))
    (is (every? #(every? (fn [handler] (= :prepared (:status handler))) (:handlers %)) owned))
    (is (some #(some (fn [handler] (= :failed (:status handler)))
                    (:independent-call-handlers %)) owned)
        "The ordinary runtime remainder stays compiler-rejected")
    (is (some #(str/includes? (str (:stderr %)) "must use @rem or @mod")
              (mapcat :independent-call-handlers owned)))
    (is (not-any? :declared-constant-initializer?
                  (filter #(= 'inner-result (:declaration-name %)) operations)))
    (is (= [3 2 11 [3 2 11] [11 2 3] 1060 7 nil {:x 3 :y 6}] (:results consumer)))
    (is (:same-address? consumer))
    (is (empty? (filter #(#{:compiled :compile-failed} (:event %)) events)) (pr-str events))
    (is (empty? (filter #(and (= :disk-cache-hit (:event %)) (nil? (:bundle-id %))
                              (str/starts-with? (str (:module %)) "aguafria.jvm.")) events))
        (pr-str events))
    (is (seq hits))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))))
    (is (every? #(= (get-in producer [:bundles :packs 0 :id]) (:bundle-id %)) hits))))
