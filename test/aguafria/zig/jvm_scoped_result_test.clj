(ns aguafria.zig.jvm-scoped-result-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- preparation-state []
  {:registry @(var-get #'runtime/registry)
   :adapters @(var-get #'jvm/prepared-adapters)
   :coercions @(var-get #'jvm/prepared-coercions)
   :proofs @(var-get #'jvm/scoped-plan-proofs)})

(deftest result-transport-planning-is-pure-and-keeps-guards-outside-the-payload
  (let [context (the-ns 'aguafria.zig.jvm-scoped-result-test)
        before (preparation-state)
        plan (#'jvm/expression-adapter-plan
              context '(aguafria.keyword/+ input 1) [{:name 'input :type :u64}]
              'scopedResult
              {:preconditions ['((field __aguafria_jvm :scopedCaptureMatches) input 103)]})
        descriptor (last (:declarations plan))
        source (binding [emitter/*registered-declaration-names* (set (map :name (:declarations plan)))]
                 (emitter/emit-module
                  (str (ns-name context))
                  (mapv #(emitter/prepare-declaration context
                                                      (assoc % :module (str (ns-name context))
                                                             :implicit-return? true))
                        (map #(clojure.walk/postwalk-replace
                               {(:helper-reference plan) '(a/raw "@import(\"jvm_result.zig\").__aguafria_jvm")} %)
                             (:declarations plan)))))]
    (is (= before (preparation-state)))
    (is (= 2 (count (:body descriptor))))
    (is (= 'if (ffirst (:body descriptor))))
    (is (re-find #"scopedCaptureMismatch\(\)" source))
    (is (re-find #"scopedResult\(\(input \+ 1\)\)" source))
    (is (not (re-find #"anyerror!type" source)))))

(deftest rejected-result-transport-never-publishes-helper-call-or-cleanup
  (let [module 'aguafria.zig.jvm-scoped-result-test
        before (preparation-state)
        failure
        (with-redefs-fn
          {#'jvm/validate-scoped-declaration!
           (fn [& _] (throw (ex-info "Rejected unpublished result" {:unpublished? true})))
           #'jvm/scoped-constant-profile! (fn [& _] nil)}
          #(try (jvm/precompile-scoped!
                 {:caller module :form '(a/with-block :owned (k/break :owned 11))
                  :captures [] :types [] :result? true})
                nil
                (catch clojure.lang.ExceptionInfo error error)))]
    (is (= "Rejected unpublished result" (ex-message failure)))
    (is (:unpublished? (ex-data failure)))
    (is (= before (preparation-state)))))

(deftest empty-preconditions-preserve-ordinary-expression-identity
  (let [context (the-ns 'aguafria.zig.jvm-scoped-result-test)
        expression '(aguafria.keyword/+ input 1)
        parameters [{:name 'input :type :u64}]
        ordinary (#'jvm/expression-adapter-plan context expression parameters 'result nil)
        empty-guards (#'jvm/expression-adapter-plan context expression parameters 'result {:preconditions []})]
    (is (= (:function ordinary) (:function empty-guards)))
    (is (= (:adapter-key ordinary) (:adapter-key empty-guards)))
    (is (= (:declarations ordinary) (:declarations empty-guards)))))

(deftest valid-bridge-rejection-keeps-the-invoked-adapter-prepared
  (let [prepared (atom #{})
        released (atom [])
        key [:validated-guard-transport]
        function 'aguafria.zig.jvm-scoped-result-test/guarded]
    (with-open [arena (java.lang.foreign.Arena/ofConfined)]
      (let [storage (.allocateFrom arena "{:aguafria.jvm/scoped-capture-mismatch true}")
            failure
            (with-redefs-fn
              {#'jvm/prepared-adapters prepared
               #'runtime/invoke-with-result! (fn [_ _ consumer] (consumer (.address storage) (constantly (fn []))))
               #'runtime/invoke! (fn [release arguments] (swap! released conj [release arguments]))}
              #(try (#'jvm/invoke-adapter! {:function function :adapter-key key
                                            :context (the-ns 'aguafria.zig.jvm-scoped-result-test)
                                            :release 'release :parameters [] :expression nil} [])
                    nil
                    (catch clojure.lang.ExceptionInfo error error)))]
        (is (= :scoped-capture-value-changed (:reason (ex-data failure))))
        (is (= #{key} @prepared))
        (is (= [['release [(.address storage)]]] @released))))))

(deftest propagated-application-error-is-not-a-result-bridge-failure
  (let [application (ex-info "Application error"
                             {:aguafria/phase :native-application
                              :error-name :AguafriaScopedCaptureValueChanged})
        failure
        (with-redefs-fn
          {#'jvm/prepare-scoped-plan! (fn [& _] {:transport-plan {} :propagates-errors? true})
           #'jvm/invoke-adapter! (constantly ::application-payload)
           #'value/try-value! (fn [_] (throw application))
           #'emitter/scoped-result-context-required? (constantly false)}
          #(try (jvm/invoke-scoped! 'aguafria.zig.jvm-scoped-result-test
                                    '(a/with-block :owned (k/break :owned 11)) {} true)
                nil
                (catch clojure.lang.ExceptionInfo error error)))]
    (is (identical? application failure))
    (is (= :AguafriaScopedCaptureValueChanged (:error-name (ex-data failure))))
    (is (= :native-application (:aguafria/phase (ex-data failure))))))

(deftest native-rejection-leaves-the-result-transport-unpublished
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.jvm-scoped-result-fixture :reload))
  (let [context (the-ns 'aguafria.zig.jvm-scoped-result-fixture)
        before (preparation-state)
        plan (#'jvm/expression-adapter-plan
              context
              '(a/raw "(struct { var value: u64 = 0; fn pointer() *u64 { return &value; } }).pointer()")
              [] 'scopedResult nil)
        failure (try
                  (#'jvm/validate-scoped-declaration!
                   context (last (:declarations plan))
                   (select-keys plan [:helper-source :helper-reference]))
                  nil
                  (catch clojure.lang.ExceptionInfo error error))]
    (is (:unpublished? (ex-data failure)))
    (is (re-find #"retained scoped result has unsupported value semantics" (:stderr (ex-data failure))))
    (is (= before (preparation-state)))))

(deftest retained-result-subset-is-decided-by-the-native-compiler
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.jvm-scoped-result-fixture :reload))
  (let [module 'aguafria.zig.jvm-scoped-result-fixture
        before (preparation-state)
        helper (slurp (io/resource "aguafria/jvm_result.zig"))]
    (doseq [[label source supported?]
            [["scalar" "pub fn check(value: u64) usize { return H.scopedResult(value); }" true]
             ["application error" "pub fn check(value: error{AguafriaScopedCaptureValueChanged}!u32) usize { return H.scopedResult(value); }" true]
             ["type value" "pub fn check() usize { return H.scopedResult(u16); }" true]
             ["borrowed pointer" "pub fn check(value: *const u64) usize { return H.scopedResult(value); }" false]
             ["borrowed optional" "pub fn check(value: ?*const u64) usize { return H.scopedResult(value); }" false]
             ["pointer aggregate" "pub fn check(value: struct { child: *const u64 }) usize { return H.scopedResult(value); }" false]]]
      (let [result (runtime/inspect-module!
                    module
                    (fn [_]
                      {:declarations []
                       :source (str "const H = @import(\"jvm_result.zig\").__aguafria_jvm;\n" source
                                    "\ntest { _ = &check; }\n")
                       :files {"jvm_result.zig" helper}}))]
        (is (= (if supported? 0 1) (:exit result)) (str label ": " (:err result)))
        (when-not supported?
          (is (re-find #"retained scoped result has unsupported value semantics" (:err result)) label))
        (is (= before (preparation-state)))))))

(deftest original-value-and-statement-proof-placements-keep-their-contracts
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.jvm-scoped-result-fixture :reload)
    (require 'aguafria.zig.jvm-scoped-immutable-fixture :reload))
  (doseq [[module resource test-name types]
          [['aguafria.zig.jvm-scoped-result-fixture
            "aguafria/zig/jvm_scoped_result_fixture.clj" 'labeled-result {'label :u64 'selector :u64}]
           ['aguafria.zig.jvm-scoped-immutable-fixture
            "aguafria/zig/jvm_scoped_immutable_fixture.clj" 'successful-closed-error-constant
            {'value [:error-union [:error-set [:Rejected]] :u32]}]]]
    (let [forms (with-open [reader (java.io.PushbackReader. (io/reader (io/resource resource)))]
                  (binding [*read-eval* false]
                    (into [] (take-while some?) (repeatedly #(read {:eof nil} reader)))))
          body (first (filter #(= test-name (second %)) forms))
          binding (nth body 2)
          scope (if (= test-name 'labeled-result) (nth (second binding) 5) (nth binding 2))
          before (emitter/emit-module (str module) (runtime/registered-declarations module))
          proof (discovery/prove-scoped-constants! module scope types)]
      (is (:proven? proof) (pr-str proof))
      (is (= :zig-compiler (:basis proof)))
      (is (= (set (keys types)) (set (keys (:candidates proof)))))
      (is (= before (emitter/emit-module (str module) (runtime/registered-declarations module)))))))

(defn- result-jvm [cache producer?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.precompile
                    'aguafria.zig.explain 'aguafria.zig.value 'aguafria.zig.jvm
                    'clojure.java.io 'clojure.java.shell)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (if ~producer?
             (let [fail!# (fn [& _#] (throw (ex-info "Producer invoked native code" {})))
                   report# (with-redefs [aguafria.zig.runtime/invoke! fail!#
                                         aguafria.zig.runtime/invoke-with-result! fail!#]
                             (aguafria.zig.precompile/precompile!
                              {:analyze ['learn.example.test-switch 'aguafria.zig.jvm-scoped-result-fixture]
                               :parallelism 1 :report-file ~(str cache "/report.edn")}))]
               (prn {:coverage (:coverage report#) :bundles (:bundles report#)
                     :scopes (vec (for [entry# (:analysis report#) operation# (:operations entry#)
                                        :when (:scoped-form operation#)]
                                    (assoc (select-keys operation# [:id :declaration-name :handlers :scope-result?])
                                           :namespace (:namespace entry#))))}))
             (let [events# (atom []) commands# (atom []) sh# clojure.java.shell/sh
                   forms#
                   (fn [resource#]
                     (with-open [reader# (java.io.PushbackReader.
                                          (clojure.java.io/reader (clojure.java.io/resource resource#)))]
                       (binding [*read-eval* false]
                         (into [] (take-while some?) (repeatedly #(read {:eof nil} reader#))))))
                   round#
                   (fn []
                     (vec (for [[namespace# resource#]
                                [['learn.example.test-switch "learn/example/test_switch.clj"]
                                 ['aguafria.zig.jvm-scoped-result-fixture "aguafria/zig/jvm_scoped_result_fixture.clj"]]
                                form# (forms# resource#)
                                :when (and (seq? form#) (= (symbol "a/deftest") (first form#)))]
                            (binding [*ns* (the-ns namespace#)]
                              {:name (second form#) :namespace namespace#
                               :value (aguafria.zig/value (eval (cons (symbol "do") (drop 2 form#))))}))))
                   selected# (first (filter #(= (symbol "retained-label-result") (second %))
                                            (forms# "aguafria/zig/jvm_scoped_result_fixture.clj")))
                   binding# (nth selected# 2)
                   label# (symbol "label")
                   scope# (nth (second binding#) 5)
                   prefix# (subvec (second binding#) 0 4)
                   pointer# (symbol "pointer")
                   alias-body#
                   (list (symbol "let") prefix#
                         (list (symbol "let") [pointer# (list 'aguafria.zig.value/address-value label# true)]
                               (list 'aguafria.zig.jvm-scoped-result-fixture/overwrite-label pointer#)
                               scope#))
                   raw-body#
                   (list (symbol "let") prefix#
                         (list (symbol ".set") (list 'aguafria.zig.value/segment label#)
                               (symbol "java.lang.foreign.ValueLayout/JAVA_LONG") 0 (list (symbol "long") 104))
                         scope#)
                   output#
                   (with-redefs [clojure.java.shell/sh
                                 (fn [& command#]
                                   (swap! commands# conj (vec command#))
                                   (apply sh# command#))]
                     (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                       (require 'learn.example.test-switch 'aguafria.zig.jvm-scoped-result-fixture)
                       (let [mutations#
                             (binding [*ns* (the-ns 'aguafria.zig.jvm-scoped-result-fixture)]
                               (mapv (fn [form#]
                                       (try (eval form#) :missed-mutation
                                            (catch clojure.lang.ExceptionInfo error#
                                              (:reason (ex-data error#)))))
                                     [alias-body# raw-body#]))
                             marker-form#
                             (first (filter #(= (symbol "application-marker-struct") (second %))
                                            (forms# "aguafria/zig/jvm_scoped_result_fixture.clj")))
                             marker# (binding [*ns* (the-ns 'aguafria.zig.jvm-scoped-result-fixture)]
                                       (aguafria.zig/value (eval (second (second (nth marker-form# 2))))))
                             first# (round#) cold# @commands#
                             _# (reset! commands# []) second# (round#)
                             warm# @commands#]
                         {:results [first# second#] :bootstrap-commands cold#
                          :application-marker marker#
                          :warm-commands warm# :mutations mutations#
                          :post-bootstrap-commands @commands#})))]
               (prn (assoc output# :events @events#))))
           (shutdown-agents))
        process (shell/sh (str (System/getProperty "java.home") "/bin/java")
                          "--enable-native-access=ALL-UNNAMED" "-cp" (System/getProperty "java.class.path")
                          "clojure.main" "-e" (pr-str code))]
    (spit (str cache (if producer? "/producer-process.edn" "/consumer-process.edn")) (pr-str process))
    (when-not (zero? (:exit process))
      (throw (ex-info "Guarded scoped result JVM failed" {:cache cache :process process})))
    (edn/read-string (:out process))))

(deftest guarded-public-results-reuse-a-compile-only-producer-pack
  (let [cache (str (java.nio.file.Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "scoped-result-" (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (result-jvm cache true)
        _ (spit (str cache "/producer.edn") (pr-str producer))
        _ (when-not (and (zero? (get-in producer [:coverage :namespaces :baseline-failures] -1))
                         (not-any? #(= :failed (:status %)) (mapcat :handlers (:scopes producer))))
            (throw (ex-info "Guarded result producer incomplete" {:cache cache :producer producer})))
        consumer (result-jvm cache false)
        _ (spit (str cache "/consumer.edn") (pr-str consumer))
        events (:events consumer)
        hits (filter #(= :bundle-cache-hit (:event %)) events)
        misses (filter #(or (#{:compiled :compile-failed} (:event %))
                            (and (= :disk-cache-hit (:event %)) (nil? (:bundle-id %))
                                 (str/starts-with? (str (:module %)) "aguafria.jvm."))) events)]
    (is (= 9 (count (first (:results consumer)))))
    (is (= '{switch-simple {:ok nil}, switch-inside-function nil,
             labeled-result {:ok nil}, retained-label-result {:ok nil},
             application-error-result nil, retained-type-result {:ok nil},
             ordinary-runtime-result {:ok nil}, mutation-tools {:ok nil},
             application-marker-struct {:ok nil}}
           (into {} (map (juxt :name :value)) (first (:results consumer)))))
    (is (= (first (:results consumer)) (second (:results consumer))))
    (is (= [:scoped-capture-value-changed :scoped-capture-value-changed] (:mutations consumer)))
    (is (= {:aguafria.jvm/scoped-capture-mismatch true} (:application-marker consumer)))
    (is (seq hits))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))))
    (is (every? #(= (get-in producer [:bundles :packs 0 :id]) (:bundle-id %)) hits))
    (is (empty? misses) (pr-str misses))
    (is (every? #(or (and (= 4 (count %)) (= "version" (second %)) (= :dir (nth % 2)))
                     (and (= 3 (count %)) (= ["build-lib" "--show-builtin"] (subvec % 1))))
                (:bootstrap-commands consumer)))
    (is (<= (count (:bootstrap-commands consumer)) 2))
    (is (empty? (:warm-commands consumer)))
    (is (empty? (:post-bootstrap-commands consumer)))))
