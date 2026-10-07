(ns aguafria.zig.jvm-scoped-immutable-test
  (:require [aguafria.zig.jvm :as jvm]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]))

(defn- preparation-state [module]
  {:declarations (runtime/registered-declarations module)
   :adapters @(var-get #'aguafria.zig.jvm/prepared-adapters)
   :coercions @(var-get #'aguafria.zig.jvm/prepared-coercions)
   :proofs @(var-get #'aguafria.zig.jvm/scoped-plan-proofs)})

(deftest rejected-scoped-adapter-is-never-published
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.jvm-scoped-immutable-fixture :reload))
  (let [module 'aguafria.zig.jvm-scoped-immutable-fixture
        before (preparation-state module)
        failure
        (try
          (jvm/precompile-scoped!
           {:caller module
            :form '(aguafria.zig/if-capture-stmt
                    {:payload [item] :error [err]} value
                    (try (aguafria.std.testing/expectEqual item 5))
                    (aguafria.zig/block
                     (aguafria.keyword/= :_ err)
                     (aguafria.keyword/unreachable)))
            :captures '[value]
            :types [[:error-union :anyerror :u32]
                    [:*const [:error-union :anyerror :u32]]]
            :result? false})
          nil
          (catch clojure.lang.ExceptionInfo error error))]
    (is (some? failure))
    (is (:unpublished? (ex-data failure)))
    (is (re-find #"error set is discarded" (or (:stderr (ex-data failure)) "")))
    (is (= before (preparation-state module)))
    (is (= :prepared
           (:status
            (jvm/precompile-scoped!
             {:caller module
              :form '(aguafria.zig/block
                      (try (aguafria.std.testing/expect true)))
              :captures [] :types [] :result? false}))))))

(defn- fixture-jvm [cache producer?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.precompile 'aguafria.zig.jvm
                    'aguafria.zig.explain 'aguafria.zig.value 'clojure.java.io
                    'clojure.java.shell)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (binding [aguafria.zig.runtime/*source-only-registration?* true]
             (require 'aguafria.zig.jvm-scoped-immutable-fixture))
           (if ~producer?
             (let [fail!# (fn [& _#] (throw (ex-info "Producer invoked native code" {})))]
               (with-redefs [aguafria.zig.runtime/invoke! fail!#
                             aguafria.zig.runtime/invoke-with-result! fail!#]
                 (let [report# (aguafria.zig.precompile/precompile!
                                {:analyze ['aguafria.zig.jvm-scoped-immutable-fixture]
                                 :parallelism 1 :report-file ~(str cache "/report.edn")})]
                   (prn (select-keys report# [:bundles :coverage])))))
             (let [events# (atom [])
                   processes# (atom [])
                   original-sh# clojure.java.shell/sh
                   forms# (with-open [reader# (java.io.PushbackReader.
                                              (clojure.java.io/reader
                                               (clojure.java.io/resource
                                                "aguafria/zig/jvm_scoped_immutable_fixture.clj")))]
                            (vec (take-while some? (repeatedly #(read {:eof nil} reader#)))))
                   tests# (filter #(and (seq? %) (= "a/deftest" (str (first %)))) forms#)
                   bodies# (mapv #(cons (symbol "do") (drop 2 %)) tests#)
                   closed# (first (filter #(= "successful-closed-error-constant" (str (second %))) tests#))
                   binding# (nth closed# 2)
                   scope# (nth binding# 2)
                   value# (symbol "value")
                   alias# (symbol "alias")
                   mutation# (list 'aguafria.zig.jvm-scoped-immutable-fixture/overwrite-error alias#)
                   alias-body#
                   (list (symbol "let") (second binding#)
                         (list (symbol "let") [alias# (list 'aguafria.zig.value/address-value value# true)]
                               mutation# scope#))
                   raw-body#
                   (list (symbol "let") (vec (concat (second binding#)
                                                      [alias# (list 'aguafria.keyword/var 0
                                                                     [:error-union [:error-set [:Rejected]] :u32])]))
                         (list 'aguafria.keyword/= alias# (list 'aguafria.zig/error-value :Rejected))
                         (list (symbol ".copyFrom")
                               (list 'aguafria.zig.value/segment value#)
                               (list 'aguafria.zig.value/segment alias#))
                         scope#)
                   outcome#
                   (with-redefs [clojure.java.shell/sh
                                 (fn [& args#]
                                   (swap! processes# conj (vec args#))
                                   (apply original-sh# args#))]
                     (binding [*ns* (the-ns 'aguafria.zig.jvm-scoped-immutable-fixture)
                               aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                       (let [first# (mapv eval bodies#)
                             cold-count# (count @processes#)
                             second# (mapv eval bodies#)
                             warm-count# (- (count @processes#) cold-count#)]
                       {:first first#
                        :second second#
                        :warm-process-count warm-count#
                        :mutations
                        (mapv (fn [form#]
                                (try (eval form#) :missed-mutation
                                     (catch clojure.lang.ExceptionInfo error#
                                       (:reason (ex-data error#)))))
                              [alias-body# raw-body#])})))]
               (prn (assoc outcome# :events @events# :processes @processes#))))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (spit (str cache (if producer? "/producer-process.edn" "/consumer-process.edn"))
          (pr-str result))
    (when-not (zero? (:exit result))
      (throw (ex-info "Immutable scoped fixture JVM failed" {:cache cache :result result})))
    (edn/read-string (:out result))))

(deftest compiler-proven-captures-check-live-values-and-reuse-the-prepared-pack
  (let [cache (str (java.nio.file.Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "scoped-immutable-" (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (fixture-jvm cache true)
        _ (spit (str cache "/producer.edn") (pr-str producer))
        _ (when-not (zero? (get-in producer [:coverage :runtime-candidates :not-fully-prepared] -1))
            (throw (ex-info "Immutable producer incomplete" {:cache cache :producer producer})))
        consumer (fixture-jvm cache false)
        _ (spit (str cache "/consumer.edn") (pr-str consumer))
        events (:events consumer)
        hits (filter #(= :bundle-cache-hit (:event %)) events)
        misses (filter #(or (#{:compiled :compile-failed} (:event %))
                            (and (= :disk-cache-hit (:event %)) (nil? (:bundle-id %)))) events)]
    (is (= (vec (repeat 6 nil)) (:first consumer)))
    (is (= (:first consumer) (:second consumer)))
    (is (= [:scoped-capture-value-changed :scoped-capture-value-changed] (:mutations consumer)))
    (is (seq hits))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))))
    (is (every? #(= (get-in producer [:bundles :packs 0 :id]) (:bundle-id %)) hits))
    (is (empty? misses) (pr-str misses))
    (is (zero? (:warm-process-count consumer)))
    (is (<= (count (:processes consumer)) 1) (pr-str (:processes consumer)))
    (is (every? #(and (#{2 4} (count %)) (= "version" (second %))
                     (or (= 2 (count %)) (= :dir (nth % 2)))) (:processes consumer))
        (pr-str (:processes consumer)))))

(deftest native-proof-rejects-runtime-values-and-unsafe-equality-shapes
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.jvm-scoped-immutable-fixture :reload))
  (doseq [[label body expected]
          [["runtime value"
            "fn check(value: anyerror!u32) void { comptime { H.proveScopedCapture(value, @as(anyerror!u32, 0)); } } test { check(error.Rejected); }"
            #"unable to resolve comptime value"]
           ["mutated storage"
            "test { var value: anyerror!u32 = 0; value = error.Rejected; comptime { H.proveScopedCapture(value, @as(anyerror!u32, 0)); } }"
            #"unable to resolve comptime value"]
           ["different value"
            "test { comptime { H.proveScopedCapture(@as(u32, 1), @as(u32, 0)); } }"
            #"scoped capture value differs"]
           ["different type"
            "test { comptime { H.proveScopedCapture(@as(u32, 0), @as(u64, 0)); } }"
            #"scoped capture type differs"]
           ["floating zeros"
            "test { comptime { H.proveScopedCapture(@as(f32, -0.0), @as(f32, 0.0)); } }"
            #"unsupported value semantics"]
           ["pointer"
            "const value: u32 = 0; test { comptime { H.proveScopedCapture(&value, &value); } }"
            #"unsupported value semantics"]
           ["slice"
            "test { comptime { H.proveScopedCapture(@as([]const u8, \"a\"), @as([]const u8, \"a\")); } }"
            #"unsupported value semantics"]]]
    (let [result (runtime/inspect-module!
                  'aguafria.zig.jvm-scoped-immutable-fixture
                  (fn [_]
                    {:source (str "const H = @import(\"jvm_result.zig\").__aguafria_jvm;\n" body)
                     :files {"jvm_result.zig" (slurp (io/resource "aguafria/jvm_result.zig"))}}))]
      (is (= 1 (:exit result)) label)
      (is (re-find expected (:err result)) (str label ": " (:err result))))))

(deftest address-observing-scopes-never-acquire-a-value-only-profile
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.jvm-scoped-immutable-fixture :reload))
  (doseq [observer ['(aguafria.keyword/& value)
                    '(aguafria.keyword/& (aguafria.zig/field value :member))
                    '(aguafria.zig/slice value 0 1)
                    '(aguafria.zig/raw "&value")]]
    (with-redefs [runtime/inspect-module!
                  (fn [& _] (throw (ex-info "Unsafe scope reached optional proof compilation" {})))]
      (let [proof (discovery/prove-scoped-constants!
                    'aguafria.zig.jvm-scoped-immutable-fixture
                    (list 'aguafria.zig/block observer)
                    {'value :u32})]
        (is (false? (:proven? proof)))
        (is (= :scoped-storage-identity-observed (:reason proof)))))))
