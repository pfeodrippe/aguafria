(ns aguafria.zig.discovery-test
  (:require [aguafria.keyword :as k]
            [aguafria.std.c :as c]
            [aguafria.zig :as a]
            [aguafria.zig.artifact :as artifact]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.explain :as explain]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.precompile :as precompile]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(deftest compiler-type-search-reuses-visited-callback-and-field-types
  (let [root (.getAbsolutePath (io/file "test/fixtures/inspection_reachable_graph/main.zig"))
        probe (.getAbsolutePath (io/file "resources/aguafria/operation_probe.zig"))
        result (shell/sh (runtime/zig-executable) "test" "--test-no-exec" "-fno-emit-bin"
                         "--dep" "operation_probe" (str "-Mroot=" root)
                         (str "-Moperation_probe=" probe))]
    (is (zero? (:exit result)) (:err result))
    (is (str/blank? (:err result)))))

(deftest preparation-keeps-test-and-runtime-contexts-distinct
  (let [contexts (atom [])
        operation {:status :observed :function 'aguafria.keyword/+
                   :signatures [[:u32 :u32]]}
        test-operation (assoc operation :function 'aguafria.keyword/- :declaration-kind :test)
        report (with-redefs [discovery/analyze!
                             (fn [_] {:operations [(assoc operation :declaration-kind :fn)
                                                   test-operation test-operation]})
                             jvm/precompile-call!
                             (fn [{:keys [function]}]
                               (swap! contexts conj runtime/*native-test-context?*)
                               (when (and (= 'aguafria.keyword/- function)
                                          (not runtime/*native-test-context?*))
                                 (throw (ex-info "Test environment required"
                                                 {:aguafria/phase :zig-compile})))
                               {:status :prepared})]
                 (with-redefs-fn {#'discovery/prepare-declared-functions! (constantly [])}
                   #(discovery/prepare! 'fixture.context)))]
    (is (= [false false true] @contexts))
    (is (= [:runtime :test :test] (mapv :enclosing-context (:operations report))))
    (is (= [:runtime :test :test]
           (mapv #(get-in % [:handlers 0 :execution-context]) (:operations report))))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers (:operations report))))
    (is (false? runtime/*native-test-context?*))))

(deftest preparation-does-not-change-unrelated-failures-to-test-context
  (doseq [[kind phase] [[:fn :zig-compile] [:test :adapter-planning]]]
    (let [contexts (atom [])
          report (with-redefs [discovery/analyze!
                               (fn [_] {:operations [{:status :observed :declaration-kind kind
                                                      :function 'aguafria.keyword/+
                                                      :signatures [[:u32 :u32]]}]})
                               jvm/precompile-call!
                               (fn [_]
                                 (swap! contexts conj runtime/*native-test-context?*)
                                 (throw (ex-info "Unrelated failure" {:aguafria/phase phase})))]
                   (with-redefs-fn {#'discovery/prepare-declared-functions! (constantly [])}
                     #(discovery/prepare! 'fixture.context)))]
      (is (= [false] @contexts))
      (is (= :failed (get-in report [:operations 0 :handlers 0 :status])))
      (is (= "Unrelated failure" (get-in report [:operations 0 :handlers 0 :message]))))))

(defn- test-context-jvm [cache prepare?]
  (let [code `(do
                (require 'aguafria.zig 'aguafria.zig.jvm 'aguafria.zig.runtime
                         'aguafria.zig.precompile 'aguafria.zig.explain)
                (aguafria.zig.runtime/configure! {:cache-dir ~cache})
                (binding [aguafria.zig.runtime/*source-only-registration?* true]
                  (require 'aguafria.zig.discovery-test-context-fixture))
                (if ~prepare?
                  (with-redefs [aguafria.zig.runtime/invoke!
                                (fn [& _#] (throw (ex-info "Executed a body" {})))
                                aguafria.zig.runtime/invoke-with-result!
                                (fn [& _#] (throw (ex-info "Executed a body" {})))]
                    (let [report# (aguafria.zig.precompile/precompile!
                                   {:analyze ['aguafria.zig.discovery-test-context-fixture]
                                    :report-file ~(str cache "/report.edn")})
                          analysis# (first (:analysis report#))]
                      (assert (zero? (get-in analysis# [:baseline :exit])))
                      (assert (not (:compiler-errors? analysis#)))
                      (assert (empty? (:probe-failures analysis#)))
                      (binding [aguafria.zig.runtime/*native-test-context?* true]
                        (aguafria.zig.jvm/precompile-method!
                         {:receiver {:comptime-type 'aguafria.zig.discovery-test-context-fixture/Helpers}
                          :member :is-test :args []}))
                      (prn {:operations (:operations analysis#)})))
                  (let [events# (atom [])
                        outputs# (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                                   (mapv
                                    (fn [test?#]
                                      (binding [aguafria.zig.runtime/*native-test-context?* test?#]
                                        (let [helpers# (var-get (resolve 'aguafria.zig.discovery-test-context-fixture/Helpers))]
                                          (cond-> [(aguafria.zig/value ((:is-test helpers#)))]
                                            test?# (conj (aguafria.zig/value ((:require-test helpers#))))))))
                                    [false true false true]))]
                    (prn {:outputs outputs# :events @events#})))
                (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Test-context JVM failed" result)))
    (edn/read-string (:out result))))

(deftest real-test-context-adapters-survive-restart-without-contaminating-runtime
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "test-context-" (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (test-context-jvm cache true)
        test-operations (filter #(= :test (:enclosing-context %)) (:operations prepared))
        restarted (test-context-jvm cache false)
        events (:events restarted)]
    (is (seq test-operations))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers test-operations))
        (pr-str test-operations))
    (is (some #(= :test (:execution-context %)) (mapcat :handlers test-operations)))
    (is (= [[false] [true 7] [false] [true 7]] (:outputs restarted)))
    (is (empty? (filter #(= :compiled (:event %)) events)) (pr-str events))
    (is (seq (filter #(= :disk-cache-hit (:event %)) events))
        "Real test-environment libraries remain standalone, outside runtime bundles")
    (is (seq (filter #(= :memory-cache-hit (:event %)) events)))))

(deftest nested-signature-catalog-uses-emitted-member-names
  (let [catalog (#'discovery/container-declaration-catalog
                 *ns* "Root" 'fixture/Root
                 '(aguafria.zig/container {:kind :struct}
                                          [(aguafria.zig/fn-decl is-ready? :bool [] true)
                                           (aguafria.zig/fn-decl fn :bool [] true)
                                           (aguafria.zig/fn-decl exact :bool
                                                                 {:zig/name "exactZig"} [] true)]))]
    (is (= ["@TypeOf(Root.is_ready_q)"
            "@TypeOf(Root.@\"fn\")"
            "@TypeOf(Root.exactZig)"]
           (mapv first catalog)))
    (is (= ['(aguafria.keyword/TypeOf (aguafria.zig/field fixture/Root :is-ready?))
            '(aguafria.keyword/TypeOf (aguafria.zig/field fixture/Root :fn))
            '(aguafria.keyword/TypeOf (aguafria.zig/field fixture/Root "exactZig"))]
           (mapv #(edn/read-string (second %)) catalog)))))

(deftest nested-type-initializers-exclude-their-dependent-catalog-roots
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-alias-initializer-fixture :reload))
  (let [module 'aguafria.zig.discovery-alias-initializer-fixture
        fail! (fn [& _] (throw (ex-info "Discovery executed native code" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (binding [runtime/*compile-only?* true]
                   (discovery/prepare! module)))
        call (first (filter #(= (symbol (str module) "make-box") (:function %))
                            (:operations report)))
        metadata (meta (ns-resolve module 'make-box))]
    (is (zero? (get-in report [:baseline :exit])) (:diagnostics report))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (empty? (:probe-failures report)))
    (is (= 'Owner (:root-declaration-name call)))
    (is (= #{[{:comptime-type :u32} {:literal 4 :type :comptime_int}]}
           (set (:signatures call))))
    (is (every? #(= :prepared (:status %)) (:handlers call)) (pr-str (:handlers call)))
    (is (= ["comptime" "comptime"]
           (mapv #(get-in % [:properties :zig/prefix]) (jvm/call-parameters metadata))))))

(deftest nested-function-parameters-retain-compiler-identities
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-nested-parameters-fixture :reload))
  (let [fail! (fn [& _] (throw (ex-info "Discovery executed native code" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (binding [runtime/*compile-only?* true]
                   (discovery/prepare! 'aguafria.zig.discovery-nested-parameters-fixture)))
        fields (filter #(and (= :field (:storage-kind %))
                             (contains? #{:amount :other :value} (:member %)))
                       (:operations report))]
    (is (zero? (get-in report [:baseline :exit])))
    (is (= :zig-compiler (:basis report)))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (empty? (:probe-failures report)))
    (is (= 3 (count fields)))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers (:operations report)))
        (pr-str (mapcat :handlers (:operations report))))
    (doseq [field fields]
      (is (= :observed (:status field)))
      (is (not-any? nil? (tree-seq coll? seq (:signatures field)))
          (pr-str (select-keys field [:form :signatures])))
      (is (seq? (get-in field [:signatures 0 0]))
          "An anonymous parameter retains its compiler reflection expression"))))

(deftest constant-container-roots-analyze-concrete-method-bodies
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-const-container-fixture :reload))
  (let [fail! (fn [& _] (throw (ex-info "Discovery executed native code" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (discovery/prepare! 'aguafria.zig.discovery-const-container-fixture))
        operations (filter #(contains? #{'increment 'twice} (:declaration-name %))
                           (:operations report))]
    (is (zero? (get-in report [:baseline :exit])) (:diagnostics report))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (empty? (:probe-failures report)))
    (is (= 2 (count operations)))
    (is (every? #(= :observed (:status %)) operations) (pr-str operations))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers operations))
        (pr-str operations))
    (let [increment (:increment (var-get (find-var
                                          'aguafria.zig.discovery-const-container-fixture/Code)))
          twice (:twice (var-get (find-var
                                  'aguafria.zig.discovery-const-container-fixture/Cell)))]
      (is (= 8 (a/value (increment 7))))
      (is (= 14 (a/value (twice 7)))))))

(deftest private-nested-containers-analyze-concrete-methods-without-execution
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-private-nested-fixture :reload))
  (let [module 'aguafria.zig.discovery-private-nested-fixture
        declaration (first (filter #(= 'Owner (:name %)) (runtime/registered-declarations module)))
        source (emitter/emit-module module [declaration])
        fail! (fn [& _] (throw (ex-info "Discovery executed native code" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (discovery/prepare! module))
        operations (filter #(contains? #{'increment 'twice 'subtract} (:declaration-name %))
                           (:operations report))
        generic (first (filter #(= 'unspecialized (:declaration-name %)) (:operations report)))
        construction (first (filter #(= 'aguafria.zig/init (:function %)) (:operations report)))
        hidden '(aguafria.zig/field aguafria.zig.discovery-private-nested-fixture/Owner :Hidden)]
    (is (zero? (get-in report [:baseline :exit])) (:diagnostics report))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (empty? (:probe-failures report)))
    (is (= 3 (count operations)))
    (is (every? #(= :observed (:status %)) operations) (pr-str operations))
    (is (every? #(= :u32 (get-in % [:signatures 0 0])) operations) (pr-str operations))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers operations)) (pr-str operations))
    (is (= :unobserved (:status generic)))
    (is (= [[hidden {:map {:min :u32 :max :u32}}]] (:signatures construction)))
    (is (every? #(= :prepared (:status %)) (:handlers construction)))
    (is (= 1 (count (filter :signature-position? (:operations report)))))
    (is (= #{hidden}
           (set (map #(get-in % [:signatures 0 0])
                     (filter #(and (= 'contains (:declaration-name %))
                                   (= :field (:storage-kind %))) (:operations report))))))
    (is (= ["Owner.Hidden.increment" "Owner.Hidden.contains" "Owner.Hidden.unspecialized"
            "Owner.Codes.twice" "Owner.Cells.subtract"]
           (vec (#'discovery/container-inspection-paths (the-ns module) "Owner" (:value declaration)))))
    (is (not (re-find #"pub (?:const Hidden|const Codes|const Cells|fn increment|fn twice|fn subtract)"
                      source)))
    (is (= source (emitter/emit-module module [declaration])))))

(defn- private-nested-jvm [cache prepare?]
  (let [code `(do
                (require 'aguafria.keyword 'aguafria.zig 'aguafria.zig.runtime
                         'aguafria.zig.precompile 'aguafria.zig.explain)
                (aguafria.zig.runtime/configure! {:cache-dir ~cache})
                (binding [aguafria.zig.runtime/*source-only-registration?* true]
                  (require 'aguafria.zig.discovery-private-nested-fixture))
                (if ~prepare?
                  (with-redefs [aguafria.zig.runtime/invoke!
                                (fn [& _#] (throw (ex-info "Executed a body" {})))
                                aguafria.zig.runtime/invoke-with-result!
                                (fn [& _#] (throw (ex-info "Executed a body" {})))]
                    (let [report# (aguafria.zig.precompile/precompile!
                                   {:analyze ['aguafria.zig.discovery-private-nested-fixture]
                                    :coercions [:u32]
                                    :report-file ~(str cache "/report.edn")})
                          analysis# (first (:analysis report#))]
                      (assert (zero? (get-in analysis# [:baseline :exit])))
                      (assert (not (:compiler-errors? analysis#)))
                      (assert (empty? (:probe-failures analysis#)))
                      (prn {:prepared true})))
                  (let [events# (atom [])
                        outputs# (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                                   (mapv
                                    (fn [[minimum# maximum#]]
                                      (let [range# (aguafria.zig/init
                                                    {:min (aguafria.keyword/u32 minimum#)
                                                     :max (aguafria.keyword/u32 maximum#)}
                                                    (:Hidden aguafria.zig.discovery-private-nested-fixture/Owner))
                                            lower# (:min range#)
                                            upper# (:max range#)]
                                        (mapv aguafria.zig/value
                                              [lower# upper# (aguafria.keyword/<= lower# upper#)
                                               (aguafria.keyword/<= upper# lower#)
                                               (aguafria.keyword/+ lower# 1)
                                               (aguafria.keyword/* lower# 2)
                                               (aguafria.keyword/- lower# 1)])))
                                    [[2 9] [10 21]]))]
                    (prn {:outputs outputs# :events @events#})))
                (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Private nested JVM failed" result)))
    (edn/read-string (:out result))))

(deftest private-nested-fields-reuse-the-bundle-after-restart
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "private-nested-" (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (private-nested-jvm cache true)
        restarted (private-nested-jvm cache false)
        events (:events restarted)
        library (:path (first (filter #(= :bundle-loaded (:event %)) events)))
        entries (when library
                  (set (keys (:entries (edn/read-string
                                        (slurp (io/file (.getParentFile (io/file library))
                                                        "manifest.edn")))))))]
    (is (:prepared prepared))
    (is (= [[2 9 true false 3 4 1] [10 21 true false 11 20 9]] (:outputs restarted)))
    (is (empty? (filter #(= :compiled (:event %)) events)) (pr-str events))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))) (pr-str events))
    (is (seq (filter #(= :bundle-cache-hit (:event %)) events)) (pr-str events))
    (is (empty? (filter #(= :disk-cache-hit (:event %)) events)) (pr-str events))
    (is (every? #(contains? entries
                            (artifact/key-for :bundle-entry [(:module %) (:artifact-key %)]))
                (filter :artifact-key events)) (pr-str events))))

(deftest type-refinement-retains-only-clean-isolated-observations
  (let [queries (atom [])
        inspect (fn [_ selected]
                  (swap! queries conj selected)
                  {:invalid? (contains? selected "bad")
                   :operations [{:id "good" :status :unobserved}
                                {:id "bad" :status :unobserved}
                                {:id "unrelated" :status :unobserved}]
                   :observed (cond-> {}
                               (selected "good") (assoc "good" #{[:i32]})
                               ;; Even a concrete observation from a rejected
                               ;; compiler pass must not replace the old result.
                               (selected "bad") (assoc "bad" #{[:u32]}))})
        result (with-redefs-fn
                 {#'discovery/inspect-operations! inspect
                  #'discovery/compiler-errors? :invalid?}
                 #(#'discovery/refine-type-identities!
                   'fixture.refinement
                   {:observed {"good" #{[nil]} "bad" #{[nil]}}}))]
    (is (= {"good" #{[:i32]}} (:observed result)))
    (is (= #{"bad"} (set (keys (:probe-failures result)))))
    (is (= 3 (:inspection-attempts result)))
    (is (:compiler-errors? result))
    (is (= [#{"good" "bad"} #{"good"} #{"bad"}] @queries))))

(deftest compiler-observed-function-operands-preserve-native-declarations
  (let [fail! (fn [& _] (throw (ex-info "Native execution during discovery" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (first (:analysis
                         (a/precompile!
                          {:analyze ['aguafria.zig.discovery-callback-fixture]}))))
        spawn (first (filter #(= 'aguafria.std.Thread/spawn (:function %))
                             (:operations report)))]
    (is (zero? (get-in report [:baseline :exit])))
    (is (= :observed (:status spawn)))
    (is (= {:comptime-expression 'aguafria.zig.discovery-callback-fixture/worker}
           (get-in spawn [:signatures 0 1])))
    (is (seq (:handlers spawn)))
    (is (every? #(= :prepared (:status %)) (:handlers spawn)))
    (is (every? #(= :prepared (:status %)) (:functions report))
        (pr-str (:functions report)))
    (is (= "fallback"
           (binding [emitter/*keyword-context* (find-ns 'aguafria.zig.discovery-callback-fixture)
                     emitter/*lexical-bindings* #{'worker}]
             (#'discovery/declaration-argument-schema str 'worker "fallback"))))))

(deftest generic-bodies-use-existing-source-specializations-without-execution
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-specialization-leaf-fixture :reload)
    (require 'aguafria.zig.discovery-specialization-caller-fixture :reload))
  (let [fail! (fn [& _] (throw (ex-info "Native execution during discovery" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (discovery/analyze! 'aguafria.zig.discovery-specialization-leaf-fixture))
        operations (into {} (map (juxt :declaration-name identity)) (:operations report))]
    (is (zero? (get-in report [:baseline :exit])))
    (is (= #{'(scale 5) '(scale 7) '(element-size (type [:array 3 :u8]))}
           (set (map :form (:inspection-specializations report)))))
    (is (= #{'aguafria.zig.discovery-specialization-caller-fixture/call-scale
             'aguafria.zig.discovery-specialization-caller-fixture/second-scale
             'aguafria.zig.discovery-specialization-caller-fixture/array-size}
           (set (map :caller (:inspection-specializations report)))))
    (is (= :zig-compiler (:basis report)))
    (is (= :observed (:status (operations 'scale))))
    (is (= [[:u32 {:literal 2 :type :comptime_int}]] (:signatures (operations 'scale))))
    (is (= :observed (:status (operations 'element-size))))
    (is (= [[{:comptime-type [:array 3 :u8]}]]
           (:signatures (operations 'element-size))))
    (is (some #{"--test-no-exec"} (:command report)))
    (is (zero? (:native-generation-count
                (runtime/module-info "aguafria.zig.discovery-specialization-caller-fixture"))))
    (is (= :unobserved (:status (operations 'no-call-site))))
    (is (nil? (:signatures (operations 'no-call-site))))))

(deftest type-expressions-prepare-the-ordinary-type-adapter
  (let [fail! (fn [& _] (throw (ex-info "Native execution during discovery" {})))
        report (with-redefs [runtime/invoke! fail!
                             runtime/invoke-with-result! fail!
                             runtime/module-info
                             (fn [& _]
                               (throw (ex-info "Preparation inspected runtime state" {})))]
                 (first (:analysis
                         (a/precompile!
                          {:analyze ['aguafria.zig.discovery-type-expression-fixture]}))))
        operations (filter #(= 'aguafria.zig/type (:function %)) (:operations report))]
    (is (zero? (get-in report [:baseline :exit])))
    (is (= 4 (count operations)))
    (is (every? #(= :observed (:status %)) operations))
    (is (= #{:u32 'aguafria.zig.discovery-type-expression-fixture/Point
             [:array 7 'aguafria.zig.discovery-type-expression-fixture/Point]
             [:array 5 :u16]}
           (into #{} (mapcat #(map (comp :comptime-type first) (:signatures %))) operations)))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers operations)))))

(deftest ordinary-operators-prepare-their-native-keyword-handlers
  (let [fail! (fn [& _] (throw (ex-info "Native execution during discovery" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (first (:analysis
                         (a/precompile!
                          {:analyze ['aguafria.zig.discovery-core-operator-fixture]
                           :coercions [:f32 :i32]}))))
        operations (:operations report)
        commands (atom [])
        original shell/sh]
    (is (zero? (get-in report [:baseline :exit])))
    (is (= #{'aguafria.keyword/+ 'aguafria.keyword/- 'aguafria.keyword/*
             'aguafria.keyword// 'aguafria.keyword/mod}
           (set (map :function operations))))
    (is (= 7 (count operations)))
    (is (every? #(= :observed (:status %)) operations))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers operations)))
    (is (= "(op \"+\" left right)"
           (:form (first (filter #(= 'raw-add (:declaration-name %)) operations)))))
    (with-redefs [shell/sh (fn [& arguments]
                             (swap! commands conj (take 4 arguments))
                             (apply original arguments))]
      (let [left (k/f32 8) right (k/f32 2)
            integer (k/i32 8) divisor (k/i32 3)]
        (is (= 10.0 (a/value (k/+ left right))))
        (is (= 5 (a/value (k/- integer divisor))))
        (is (= -8 (a/value (k/- integer))))
        (is (= 16.0 (a/value (k/* left right))))
        (is (= 4.0 (a/value (k// left right))))
        (is (= 2 (a/value (k/mod integer divisor))))))
    (is (empty? @commands) (str @commands))))

(deftest failed-lazy-declarations-do-not-hide-independent-compiler-observations
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-lazy-error-fixture :reload))
  (let [fail! (fn [& _] (throw (ex-info "Native execution during analysis" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (discovery/analyze! 'aguafria.zig.discovery-lazy-error-fixture))
        failed (get-in report [:root-failures 'unused-error])
        addition (first (filter #(= 'aguafria.keyword/+ (:function %))
                                (:operations report)))]
    (is (not (zero? (get-in report [:baseline :exit]))))
    (is (zero? (get-in report [:analysis-baseline :exit])))
    (is (str/includes? (:diagnostics failed) "invalid unused declaration"))
    (is (= #{'unused-error} (set (keys (:root-failures report)))))
    (is (= :observed (:status addition)))
    (is (seq (:signatures addition)))
    (is (some #(= :compiler-rejected-root (:reason %)) (:operations report)))))

(deftest adapter-identities-ignore-map-order-but-preserve-type-information
  (let [left [:* (array-map :const? true :align 1) :i32]
        right [:* (array-map :align 1 :const? true) :i32]]
    (is (= (runtime/adapter-fingerprint left) (runtime/adapter-fingerprint right)))
    (is (not= (runtime/adapter-fingerprint left)
              (runtime/adapter-fingerprint [:* {:const? true :align 2} :i32])))
    (is (not= (runtime/adapter-fingerprint (with-meta 'x {:zig/type :i32}))
              (runtime/adapter-fingerprint (with-meta 'x {:zig/type :u32}))))))

(deftest field-type-probes-preserve-recursive-containers
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-recursive-type-fixture :reload))
  (let [report (discovery/analyze! 'aguafria.zig.discovery-recursive-type-fixture)
        link (first (filter #(= 'aguafria.zig.discovery-recursive-link-fixture/Link
                                (:function %))
                            (:operations report)))]
    (is (zero? (get-in report [:baseline :exit])))
    (is (str/includes? (slurp (:source-path report)) "@import(\"std\").lang.Type"))
    (is (empty? (:probe-failures report)))
    (is (= :observed (:status link)))
    (is (= [[{:comptime-type 'aguafria.zig.discovery-recursive-type-fixture/Item}]]
           (:signatures link)))))

(deftest field-adapter-identities-match-compiler-and-live-type-references
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-nested-fields-types-fixture
             'aguafria.zig.discovery-generic-type-fixture))
  (let [prepare (fn [receiver member address]
                  (let [{:keys [module expression writer types]}
                        (#'jvm/field-view-plan receiver member address)
                        context (or (find-ns module) (create-ns module))]
                    (:function
                     (#'jvm/prepare-expression!
                      context expression [{:name 'input_0 :type (first types)}] writer))))
        receiver 'aguafria.zig.discovery-nested-fields-types-fixture/Exchange
        live (emitter/qualify-type *ns* receiver)
        generic '(aguafria.zig.discovery-generic-type-fixture/Box :i32)
        live-generic '(aguafria.zig.discovery-generic-type-fixture/Box (type :i32))]
    (is (= (prepare receiver :result [:*const receiver])
           (prepare live :result [:*const live])))
    (is (= (prepare generic :value [:*const generic])
           (prepare live-generic :value [:*const live-generic])))))

(deftest compiler-resolves-imported-nested-field-identities
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-nested-fields-fixture :reload))
  (let [fail! (fn [& _] (throw (ex-info "Native execution during discovery" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (first (:analysis
                         (precompile/precompile!
                          {:analyze ['aguafria.zig.discovery-nested-fields-fixture]}))))
        operations (filter #(= :field (:storage-kind %)) (:operations report))
        request '(aguafria.keyword/FieldType
                  (aguafria.keyword/FieldType
                   aguafria.zig.discovery-nested-fields-types-fixture/Exchange "result")
                  "request")
        actor (first (filter #(= :actor (:member %)) operations))]
    (is (zero? (get-in report [:baseline :exit])))
    (is (empty? (:probe-failures report)))
    (is (= 3 (count operations)))
    (is (= [[request [:*const request]]] (:signatures actor)))
    (is (not-any? nil? (tree-seq coll? seq (map :signatures operations))))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers operations)))
    (let [commands (atom [])
          original shell/sh]
      (with-redefs [shell/sh (fn [& arguments]
                               (swap! commands conj arguments)
                               (apply original arguments))]
        (with-open [exchange ((ns-resolve 'aguafria.zig.discovery-nested-fields-fixture
                                          'make-exchange))]
          (let [result (:result exchange)
                native-request (:request result)]
            (is (= request (value/qualified-type native-request)))
            (is (= 7 (a/value (:actor native-request)))))))
      (is (empty? @commands) (str @commands)))))

(deftest compiler-discovery-never-executes-the-example
  (let [fail! (fn [& _] (throw (ex-info "Native invocation during discovery" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (first (:analysis
                         (precompile/precompile!
                          {:analyze ['aguafria.zig.discovery-fixture]}))))
        operations (:operations report)
        additions (filter #(= 'aguafria.keyword/+ (:function %)) operations)
        signatures (set (mapcat :signatures additions))]
    (is (= :zig-compiler (:basis report)))
    (is (zero? (get-in report [:baseline :exit])))
    (is (false? (:compiler-errors? report)) (:diagnostics report))
    (is (contains? signatures [:i32 :i32]))
    (is (contains? signatures [:i16 :i16]))
    (is (contains? signatures [:i32 {:literal 7 :type :comptime_int}]))
    (is (contains? signatures [{:literal 31 :type :comptime_int}
                               {:literal 32 :type :comptime_int}]))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers additions)))
    (is (every? #(and (:file %) (:line %)) operations))
    (is (some #{"--test-no-exec"} (:command report)))
    (is (some #{"-fno-emit-bin"} (:command report)))
    (is (not (str/includes?
              (emitter/emit-module 'aguafria.zig.discovery-fixture
                                   (:definitions (runtime/module-info 'aguafria.zig.discovery-fixture)))
              "aguafria_operation_")))
    ;; Construction and the actual subform calls must hit the same adapters.
    (let [commands (atom [])
          original shell/sh]
      (with-redefs [shell/sh (fn [& arguments]
                               (swap! commands conj (take 2 arguments))
                               (apply original arguments))]
        (let [x (k/i32 10)
              y (k/i32 20)]
          (is (= 30 (a/value (k/+ x y))))
          (is (= 17 (a/value (k/+ x 7))))
          (is (= 63 (a/value (k/+ 31 32))))
          (is (= (float (/ 7.0 3.0)) (float (a/value (k/f32 (k// 7.0 3.0))))))
          (is (= [1 2] (a/value (k/as (a/array [1 2] :i32) [:vector 2 :i32]))))
          (let [array (a/array [1 2] :i32)]
            (is (= 2 (a/value (:len array))))
            (is (= 1 (a/value (a/get array 0)))))
          (let [array (k/var (a/array [1 2] :i32))]
            (k/+= (a/get array 0) 1)
            (is (= 2 (a/value (a/get array 0)))))))
      (is (empty? @commands) (str @commands)))))

(deftest namespace-errors-do-not-abort-the-inventory
  (let [report (precompile/precompile!
                {:analyze ['aguafria.no-such-namespace 'aguafria.zig.discovery-fixture]})]
    (is (= 2 (count (:analysis report))))
    (is (= :load-failed (get-in report [:analysis 0 :status])))
    (is (= :zig-compiler (get-in report [:analysis 1 :basis])))))

(deftest unused-concrete-functions-are-prepared-without-execution
  (let [fail! (fn [& _] (throw (ex-info "Native invocation during discovery" {})))
        reporter explain/*reporter*
        record! (fn [events event]
                  (swap! events conj event)
                  (when reporter (reporter event)))
        prepared-events (atom [])
        report (binding [explain/*reporter* #(record! prepared-events %)]
                 (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                   (first (:analysis
                           (precompile/precompile!
                            {:analyze ['aguafria.zig.discovery-fixture]})))))
        functions (into {} (map (juxt :function identity)) (:functions report))
        function (ns-resolve 'aguafria.zig.discovery-fixture 'add-literal)
        commands (atom [])
        ordinary-events (atom [])
        prepared-keys (into #{} (keep :artifact-key) @prepared-events)
        original shell/sh]
    ;; No other declaration calls add-literal; observing calls alone misses it.
    (is (= :prepared (:status (functions 'aguafria.zig.discovery-fixture/add-literal))))
    (is (= :specialization (:reason (functions 'aguafria.zig.discovery-fixture/generic-add))))
    (is (= :test-runner (:reason (functions 'aguafria.zig.discovery-fixture/do-not-execute))))
    (is (= 4 (count functions)))
    (binding [explain/*reporter* #(record! ordinary-events %)]
      (with-redefs [shell/sh (fn [& arguments]
                               (swap! commands conj (take 4 arguments))
                               (apply original arguments))]
        (let [native (function 35)]
          (try (is (= 42 (a/value native))) (finally (a/close! native))))))
    (is (empty? @commands) (str @commands))
    (is (seq (keep :artifact-key @ordinary-events))
        "The check must observe an actual library lookup, not an already loaded function")
    (is (every? prepared-keys (keep :artifact-key @ordinary-events))
        (str "Ordinary invocation must use keys actually prepared: "
             (mapv #(select-keys % [:event :module :artifact-key]) @ordinary-events)))))

(deftest declaration-only-imports-remain-explicitly-unsupported
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-import-member-fixture))
  (let [report (discovery/prepare! 'aguafria.zig.discovery-import-member-fixture)]
    (is (zero? (get-in report [:baseline :exit])))
    (is (= [:prepared] (mapv :status (:functions report))))
    (is (= [:observed] (mapv :status (:operations report))))
    (is (= [:declaration-only-import]
           (mapv :reason (mapcat :handlers (:operations report)))))
    (is (= [:unsupported]
           (mapv :status (mapcat :handlers (:operations report)))))))

(deftest generic-type-identities-come-from-compiler-observed-calls
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-generic-type-fixture :reload))
  (let [fail! (fn [& _] (throw (ex-info "Native invocation during discovery" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (discovery/prepare! 'aguafria.zig.discovery-generic-type-fixture))
        constructors (filter #(= 'aguafria.zig/init (:function %)) (:operations report))
        literal-constructors (filter :literal-constructor? constructors)
        commands (atom [])
        original shell/sh]
    (is (zero? (get-in report [:baseline :exit])))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (= '#{(aguafria.zig.discovery-generic-type-fixture/Box :i32)
              (aguafria.zig.discovery-generic-type-fixture/Box :u16)}
           (set (:type-identities report))))
    (is (= #{42 7 15}
           (set (map #(get-in % [:constructor-value :value]) literal-constructors))))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers literal-constructors)))
    (is (every? #(= :prepared (:status %))
                (mapcat :handlers (remove :literal-constructor? constructors))))
    (with-redefs [shell/sh (fn [& arguments]
                             (swap! commands conj (take 2 arguments))
                             (apply original arguments))]
      (doseq [[type number] [[:i32 42] [:u16 7] [:i32 15]]]
        (let [box-type ((ns-resolve 'aguafria.zig.discovery-generic-type-fixture 'Box) type)
              box (a/init {:value number} box-type)]
          (is (= number (a/value (:value box)))))))
    (is (empty? @commands) (str @commands))))

(deftest numeric-comptime-type-identities-retain-their-imports
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-sized-type-fixture :reload))
  (let [fail! (fn [& _] (throw (ex-info "Native invocation during discovery" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (discovery/prepare! 'aguafria.zig.discovery-sized-type-fixture))
        constructors (filter :literal-constructor? (:operations report))]
    (is (zero? (get-in report [:baseline :exit])) (:diagnostics report))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (empty? (:root-failures report)))
    (is (some #{'(aguafria.zig.discovery-sized-type-fixture/Buffer :u64 4)}
              (:type-identities report)))
    (is (seq constructors))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers constructors)))))

(deftest type-identities-do-not-guess-the-type-of-anytype-constants
  (let [inspect #'discovery/observed-type-identities
        operation {:id "0" :function 'example/Type :returns-type? true}
        constant {:operations [(assoc operation :parameter-types [:anytype])]
                  :observed {"0" [[{:comptime 5}]]}}]
    (is (= [] (inspect constant)))
    (is (= '[(example/Type 5)]
           (inspect (assoc-in constant [:operations 0 :parameter-types] [:usize]))))
    (is (= '[(example/Type 5)]
           (inspect (assoc constant :observed {"0" [[{:literal 5 :type :comptime_int}]]}))))
    (is (= [] (inspect (assoc constant :observed {"0" [[{:comptime-type [:* nil]}]]}))))))

(deftest generic-value-results-retain-the-ordinary-call-identity
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-call-result-fixture :reload))
  (let [fail! (fn [& _] (throw (ex-info "Executed a body" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (discovery/prepare! 'aguafria.zig.discovery-call-result-fixture))
        methods (filter :method-call? (:operations report))]
    (is (zero? (get-in report [:baseline :exit])) (:diagnostics report))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (empty? (:probe-failures report)))
    (is (= 1 (count (:call-result-identities report))))
    (is (= 2 (count methods)))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers methods))
        (pr-str (mapcat :handlers methods)))))

(defn- iterator-result-jvm [cache prepare?]
  (let [code
        `(do
           (require 'aguafria.keyword 'aguafria.zig 'aguafria.std.mem
                    'aguafria.zig.runtime 'aguafria.zig.precompile
                    'aguafria.zig.explain)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (binding [aguafria.zig.runtime/*source-only-registration?* true]
             (require 'aguafria.zig.discovery-call-result-fixture))
           (if ~prepare?
             (with-redefs [aguafria.zig.runtime/invoke!
                           (fn [& _#] (throw (ex-info "Executed a body" {})))
                           aguafria.zig.runtime/invoke-with-result!
                           (fn [& _#] (throw (ex-info "Executed a body" {})))]
               (let [report# (aguafria.zig.precompile/precompile!
                              {:analyze ['aguafria.zig.discovery-call-result-fixture]
                               :report-file ~(str cache "/report.edn")})
                     analysis# (first (:analysis report#))]
                 (assert (zero? (get-in analysis# [:baseline :exit])))
                 (assert (not (:compiler-errors? analysis#)))
                 (assert (empty? (:probe-failures analysis#)))
                 (assert (every? #(= :prepared (:status %))
                                 (mapcat :handlers (:operations analysis#))))
                 (prn {:prepared true})))
             (let [events# (atom [])
                   outputs#
                   (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                     (mapv (fn [input#]
                             (let [iterator#
                                   (aguafria.keyword/var
                                    (aguafria.std.mem/splitScalar
                                     :u8 (aguafria.keyword/as input# [:slice-const :u8]) \,))]
                               (mapv (fn [_#]
                                       (aguafria.zig/value ((:next iterator#))))
                                     (range 3))))
                           ["one,two" "a,b"]))]
               (prn {:outputs outputs# :events @events#})))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Iterator result JVM failed" result)))
    (edn/read-string (:out result))))

(deftest generic-value-result-methods-reuse-the-bundle-after-restart
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "iterator-results-" (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (iterator-result-jvm cache true)
        restarted (iterator-result-jvm cache false)
        events (:events restarted)
        library (:path (first (filter #(= :bundle-loaded (:event %)) events)))
        prepared-entries (when library
                           (set (keys (:entries (edn/read-string
                                                 (slurp (io/file (.getParentFile (io/file library))
                                                                 "manifest.edn")))))))]
    (is (:prepared prepared))
    (is (= [["one" "two" nil] ["a" "b" nil]] (:outputs restarted)))
    (is (empty? (filter #(= :compiled (:event %)) events)) (pr-str events))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))) (pr-str events))
    (is (seq (filter #(= :bundle-cache-hit (:event %)) events)) (pr-str events))
    (is (empty? (filter #(= :disk-cache-hit (:event %)) events)) (pr-str events))
    (is (every? #(contains? prepared-entries
                            (artifact/key-for :bundle-entry [(:module %) (:artifact-key %)]))
                (filter :artifact-key events)) (pr-str events))))

(deftest generic-calls-retain-concrete-parameter-context
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-generic-parameter-fixture :reload))
  (let [module 'aguafria.zig.discovery-generic-parameter-fixture
        fail! (fn [& _] (throw (ex-info "Discovery executed native code" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (discovery/prepare! module))
        call (first (filter #(= (symbol (str module) "parse-value") (:function %))
                            (:operations report)))
        options (get-in call [:signatures 0 2])]
    (is (zero? (get-in report [:baseline :exit])) (:diagnostics report))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (empty? (:probe-failures report)))
    (is (= {:comptime-expression '(object [])} options)
        (pr-str (:signatures call)))
    (is (every? #(= :prepared (:status %)) (:handlers call)) (pr-str (:handlers call)))))

(deftest comptime-object-operands-share-the-jvm-map-representation
  (let [canonical #'jvm/canonical-comptime-object]
    (is (= {} (canonical '(object []))))
    (is (= {:base 16 :nested {:enabled true}}
           (canonical '(object [[:base 16]
                                [:nested (object [[:enabled true]])]]))))
    (is (= '(object [[:value (example/read)]])
           (canonical '(object [[:value (example/read)]]))))
    (is (= '(object [[:value 1] [:value 2]])
           (canonical '(object [[:value 1] [:value 2]]))))))

(defn- generic-parameter-jvm [cache prepare?]
  (let [code
        `(do
           (require 'aguafria.keyword 'aguafria.zig 'aguafria.zig.runtime
                    'aguafria.zig.precompile 'aguafria.zig.explain
                    'clojure.edn 'clojure.java.io)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (binding [aguafria.zig.runtime/*source-only-registration?* true]
             (require 'aguafria.zig.discovery-generic-parameter-fixture))
           (if ~prepare?
             (with-redefs [aguafria.zig.runtime/invoke!
                           (fn [& _#] (throw (ex-info "Executed a body" {})))
                           aguafria.zig.runtime/invoke-with-result!
                           (fn [& _#] (throw (ex-info "Executed a body" {})))]
               (let [report# (aguafria.zig.precompile/precompile!
                              {:analyze ['aguafria.zig.discovery-generic-parameter-fixture]
                               :report-file ~(str cache "/report.edn")})
                     analysis# (first (:analysis report#))
                     calls# (filter #(= 'aguafria.zig.discovery-generic-parameter-fixture/parse-value
                                        (:function %)) (:operations analysis#))]
                 (assert (zero? (get-in analysis# [:baseline :exit])))
                 (assert (not (:compiler-errors? analysis#)))
                 (assert (empty? (:probe-failures analysis#)))
                 (assert (seq calls#))
                 (assert (every? #(= :prepared (:status %)) (mapcat :handlers calls#)))
                 (prn {:prepared-calls (count calls#)})))
             (let [events# (atom [])
                   outputs# (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                              (mapv (fn [number#]
                                      (aguafria.zig/value
                                       ((resolve 'aguafria.zig.discovery-generic-parameter-fixture/parse-value)
                                        :u16 (aguafria.keyword/u16 number#) {})))
                                    [42 0 99]))]
               (assert (= [{:ok 42} {:error {:name "InvalidNumber"}} {:ok 99}] outputs#))
               (prn {:outputs outputs# :events @events#})))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Generic parameter JVM failed" result)))
    (edn/read-string (:out result))))

(deftest generic-comptime-options-and-error-results-survive-restart
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "generic-parameters-" (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (generic-parameter-jvm cache true)
        restarted (generic-parameter-jvm cache false)
        events (:events restarted)
        library (:path (first (filter #(= :bundle-loaded (:event %)) events)))
        prepared-entries (when library
                           (set (keys (:entries (edn/read-string
                                                 (slurp (io/file (.getParentFile (io/file library))
                                                                 "manifest.edn")))))))]
    (is (= 1 (:prepared-calls prepared)))
    (is (= [{:ok 42} {:error {:name "InvalidNumber"}} {:ok 99}] (:outputs restarted)))
    (is (empty? (filter #(= :compiled (:event %)) events)) (pr-str events))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))) (pr-str events))
    (is (seq (filter #(= :bundle-cache-hit (:event %)) events)) (pr-str events))
    (is (empty? (filter #(= :disk-cache-hit (:event %)) events)) (pr-str events))
    (is (every? #(contains? prepared-entries
                            (artifact/key-for :bundle-entry [(:module %) (:artifact-key %)]))
                (filter :artifact-key events)) (pr-str events))))

(deftest compiler-observes-comptime-formats-and-typed-tuple-elements
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-tuple-fixture))
  (let [report (discovery/prepare! 'aguafria.zig.discovery-tuple-fixture)
        operations (:operations report)
        prints (filter #(= 'aguafria.std.debug/print (:function %)) operations)]
    (is (zero? (get-in report [:baseline :exit])))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (= #{[{:comptime "number={} literal={} boolean={}\n"}
              {:tuple [:i32 {:literal 7 :type :comptime_int}
                       {:representations [:bool {:comptime true}]}]}]
             [{:comptime "quoted=\"{}\"\tλ\n"} {:tuple [:f32]}]}
           (set (mapcat :signatures prints))))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers operations)))))

(deftest native-tuple-call-preparation-never-executes-integer-reflection
  (let [tuple '(aguafria.keyword/Tuple (aguafria.keyword/& [:u8]))
        fail! (fn [& _] (throw (ex-info "Native invocation during preparation" {})))
        result (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (jvm/precompile-call!
                  {:function 'aguafria.std.debug/print
                   :args [{:comptime "value={}\n"} tuple]}))]
    (is (= :prepared (:status result)))))

(deftest compiler-resolves-named-containers-and-type-members
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-nominal-fixture))
  (let [report (discovery/prepare! 'aguafria.zig.discovery-nominal-fixture)
        operations (:operations report)
        signatures (set (mapcat :signatures operations))]
    (is (zero? (get-in report [:baseline :exit])))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (contains? signatures ['aguafria.zig.discovery-nominal-fixture/Payload]))
    (is (contains? signatures ['aguafria.zig.discovery-nominal-fixture/Raw]))
    (is (contains? signatures [{:comptime-type 'aguafria.zig.discovery-nominal-fixture/Tag}
                               :void]))
    (is (contains? signatures [{:comptime-type 'aguafria.zig.discovery-nominal-fixture/Counter}
                               :void]))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers operations)))))

(deftest compiler-preserves-declared-tuple-identities
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-named-tuple-fixture))
  (let [fail! (fn [& _] (throw (ex-info "Native invocation during preparation" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (discovery/prepare! 'aguafria.zig.discovery-named-tuple-fixture))
        conversions (filter :conversion? (:operations report))
        tuple 'aguafria.zig.discovery-named-tuple-fixture/Tuple
        commands (atom [])
        original shell/sh]
    (is (zero? (get-in report [:baseline :exit])))
    (is (= #{[tuple {:tuple [{:literal 5 :type :comptime_int}
                             {:literal 6 :type :comptime_int}]}]
             [[:array 2 :u8] tuple]}
           (set (mapcat :signatures conversions))))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers conversions))
        (pr-str conversions))
    (with-redefs [shell/sh (fn [& args]
                             (when (= "build-lib" (second args))
                               (swap! commands conj (vec args)))
                             (apply original args))]
      (doseq [items [[5 6] [7 8]]]
        (with-open [native (jvm/coerce! items tuple)
                    array (k/as native [:array 2 :u8])]
          (is (= tuple (value/qualified-type native)))
          (is (= items (a/value native)))
          (is (= items (a/value array)))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Wrong number of Zig tuple elements"
                            (jvm/coerce! [5] tuple))))
    (is (empty? @commands) (pr-str @commands))))

(deftest tuple-storage-uses-native-layout-for-mixed-fields
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-named-tuple-fixture))
  (let [type 'aguafria.zig.discovery-named-tuple-fixture/Mixed
        original [1 9000000000 [-1 2]]
        changed [2 9000000001 [-3 4]]
        fail! (fn [& _] (throw (ex-info "Native invocation during preparation" {})))]
    (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
      (is (= :prepared (:status (jvm/precompile-coercion! type)))))
    (with-open [native (jvm/coerce! original type)
                mutable (k/var native)]
      (is (= original (a/value native)))
      (k/= mutable changed)
      (is (= changed (a/value mutable)))
      (is (= original (a/value native))))))

(defn- named-tuple-jvm [cache prepare?]
  (let [code (pr-str
              `(do
                 (require 'aguafria.zig.precompile 'aguafria.zig.runtime
                          'aguafria.zig.jvm 'aguafria.zig 'clojure.java.shell)
                 (aguafria.zig.runtime/configure! {:cache-dir ~cache})
                 (binding [aguafria.zig.runtime/*source-only-registration?* true]
                   (require 'aguafria.zig.discovery-named-tuple-fixture))
                 (let [commands# (atom [])
                       original# clojure.java.shell/sh]
                   (with-redefs [clojure.java.shell/sh
                                 (fn [& args#]
                                   (when (= "build-lib" (second args#))
                                     (swap! commands# conj (vec args#)))
                                   (apply original# args#))]
                     (if ~prepare?
                       (with-redefs [aguafria.zig.runtime/invoke!
                                     (fn [& _#] (throw (ex-info "Executed a body" {})))
                                     aguafria.zig.runtime/invoke-with-result!
                                     (fn [& _#] (throw (ex-info "Executed a body" {})))]
                         (let [report# (aguafria.zig.precompile/precompile!
                                        {:analyze ['aguafria.zig.discovery-named-tuple-fixture]
                                         :report-file ~(str cache "/report.edn")})]
                           (assert (every? #(= :prepared (:status %))
                                           (mapcat :handlers (mapcat :operations (:analysis report#)))))))
                       (doseq [items# [[5 6] [7 8]]]
                         (with-open [tuple# (aguafria.zig.jvm/coerce!
                                             items# 'aguafria.zig.discovery-named-tuple-fixture/Tuple)
                                     array# (aguafria.zig.jvm/coerce! tuple# [:array 2 :u8])]
                           (assert (= items# (aguafria.zig/value tuple#)))
                           (assert (= items# (aguafria.zig/value array#)))))))
                   (prn {:builds (count @commands#) :commands @commands#
                         :libraries (into #{}
                                          (comp (filter #(.isFile %))
                                                (filter #(some (fn [suffix#]
                                                                 (str/ends-with? (.getName %) suffix#))
                                                               [".dylib" ".so" ".dll"]))
                                                (map str))
                                          (file-seq (io/file ~cache)))
                         :loaded (count (filter #(seq (:functions %))
                                                (vals @(var-get (ns-resolve 'aguafria.zig.runtime (symbol "registry"))))))}))
                 (shutdown-agents)))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" code)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Named tuple JVM failed" result)))
    (edn/read-string (:out result))))

(deftest named-tuple-preparation-persists-across-jvms
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "named-tuples-" (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (named-tuple-jvm cache true)
        restarted (named-tuple-jvm cache false)]
    (is (pos? (:builds prepared)))
    (is (zero? (:loaded prepared)))
    (is (zero? (:builds restarted)) (pr-str restarted))))

(defn- runtime-tuple-jvm [cache prepare?]
  (let [code
        `(do
           (require 'aguafria.zig.precompile 'aguafria.zig.runtime
                    'aguafria.zig.jvm 'aguafria.zig 'aguafria.keyword
                    'aguafria.std.debug 'clojure.java.shell)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (binding [aguafria.zig.runtime/*source-only-registration?* true]
             (require 'aguafria.zig.discovery-runtime-tuple-fixture))
           (let [commands# (atom [])
                 original# clojure.java.shell/sh]
             (with-redefs [clojure.java.shell/sh
                           (fn [& args#]
                             (when (= "build-lib" (second args#))
                               (swap! commands# conj (vec args#)))
                             (apply original# args#))]
               (if ~prepare?
                 (with-redefs [aguafria.zig.runtime/invoke!
                               (fn [& _#] (throw (ex-info "Executed a body" {})))
                               aguafria.zig.runtime/invoke-with-result!
                               (fn [& _#] (throw (ex-info "Executed a body" {})))]
                   (let [report# (aguafria.zig.precompile/precompile!
                                  {:analyze ['aguafria.zig.discovery-runtime-tuple-fixture]
                                   :report-file ~(str cache "/report.edn")})]
                     (assert (zero? (get-in report# [:coverage :namespaces :baseline-failures]))
                             (pr-str (:coverage report#)))
                     (assert (zero? (get-in report# [:coverage :operations :not-fully-prepared]))
                             (pr-str (:coverage report#)))
                     (assert (every? #(= :prepared (:status %))
                                     (mapcat :handlers (mapcat :operations (:analysis report#)))))))
                 (doseq [initial# [12 20]]
                   (let [result# (aguafria.keyword/var
                                  (aguafria.keyword/mulWithOverflow
                                   (aguafria.keyword/u64 initial#) (aguafria.keyword/u64 10)))]
                     (aguafria.keyword/=
                      result# (aguafria.keyword/addWithOverflow
                               (aguafria.zig/get result# 0) (aguafria.keyword/u64 3)))
                     (assert (= [(+ (* initial# 10) 3) 0] (aguafria.zig/value result#)))
                     (aguafria.keyword/= result# [(aguafria.keyword/u64 99) (aguafria.keyword/u1 0)])
                     (assert (= [99 0] (aguafria.zig/value result#)))
                     (let [output# (java.io.StringWriter.)]
                       (binding [*out* output# *err* output#]
                         (aguafria.std.debug/print "{} {}" result#))
                       (assert (= "99 0" (str output#))))
                     (let [text# (str "name-" initial#)
                           tuple# (aguafria.keyword/++
                                   [(aguafria.keyword/as text# [:slice-const :u8])] [])
                           output# (java.io.StringWriter.)]
                       (binding [*out* output# *err* output#]
                         (aguafria.std.debug/print "{s}" tuple#))
                       (assert (= text# (str output#))))
                     (let [[number# overflow#] result#]
                       (assert (= 99 (aguafria.zig/value number#)))
                       (assert (= 0 (aguafria.zig/value overflow#)))))
                   (doseq [flag# [false true]]
                     (let [inner# (aguafria.keyword/++ [(aguafria.keyword/bool flag#)] [])
                           outer# (aguafria.keyword/++ [inner#] [false])]
                       (assert (= [[flag#] false] (aguafria.zig/value outer#))))))))
             (prn {:builds (count @commands#) :commands @commands#
                   :loaded (count (filter #(seq (:functions %))
                                          (vals @(var-get (ns-resolve 'aguafria.zig.runtime (symbol "registry"))))))}))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Runtime tuple JVM failed" result)))
    (edn/read-string (:out result))))

(deftest runtime-tuple-preparation-persists-across-jvms
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "runtime-tuples-" (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (runtime-tuple-jvm cache true)
        restarted (runtime-tuple-jvm cache false)]
    (is (pos? (:builds prepared)))
    (is (zero? (:loaded prepared)))
    (is (zero? (:builds restarted)) (pr-str restarted))))

(deftest coverage-never-counts-partial-or-unobserved-operations-as-prepared
  (let [summary (precompile/coverage
                 [{:namespace 'example.ok :baseline {:exit 0}
                   :functions [{:status :prepared}
                               {:status :skipped :reason :specialization}
                               {:status :failed}]
                   :operations [{:status :observed :handlers [{:status :prepared}]}
                                {:status :observed :handlers [{:status :prepared} {:status :partial}]}
                                {:status :observed :handlers [{:status :failed}]}
                                {:status :unobserved}
                                {:status :unsupported :reason :inspection-placement}]}
                  {:namespace 'example.invalid :baseline {:exit 1}}
                  {:namespace 'example.load-failure :status :load-failed}])]
    (is (= 3 (get-in summary [:namespaces :attempted])))
    (is (= {:analyzed 2 :load-failed 1} (get-in summary [:namespaces :statuses])))
    (is (= 1 (get-in summary [:namespaces :baseline-failures])))
    (is (= 1 (get-in summary [:operations :fully-prepared])))
    (is (= 4 (get-in summary [:operations :not-fully-prepared])))
    (is (= {[:partial] 1 [:failed] 1 [:unobserved] 1 [:inspection-placement] 1}
           (:incomplete-operation-groups summary)))
    (is (= {:total 3 :statuses {:prepared 1 :skipped 1 :failed 1}
            :skip-reasons {:specialization 1}}
           (:declared-functions summary)))
    (is (= {:prepared 2 :partial 1 :failed 1} (:handler-records summary)))))

(deftest coverage-groups-count-each-incomplete-operation-once
  (let [summary (precompile/coverage
                 [{:operations [{:status :observed :handlers []}
                                {:status :observed
                                 :handlers [{:status :failed} {:status :failed}
                                            {:status :partial :reason :representation-limit}]}]}])]
    (is (= {[:no-handlers] 1 [:failed :representation-limit] 1}
           (:incomplete-operation-groups summary)))
    (is (= (get-in summary [:operations :not-fully-prepared])
           (reduce + (vals (:incomplete-operation-groups summary)))))
    (is (= 2 (get-in summary [:handler-records :failed])))))

(deftest coverage-separates-declarations-without-counting-them-as-prepared
  (let [summary (precompile/coverage
                 [{:operations [{:status :unsupported :reason :type-declaration}
                                {:status :unsupported :reason :compiler-directive}
                                {:status :unsupported :reason :inspection-placement}
                                {:status :observed :handlers [{:status :prepared}]}]}])]
    (is (= 4 (get-in summary [:operations :total])))
    (is (= 1 (get-in summary [:operations :fully-prepared])))
    (is (= {:type-declaration 1 :compiler-directive 1} (:non-call-operations summary)))
    (is (= {:total 2 :fully-prepared 1 :not-fully-prepared 1} (:runtime-candidates summary)))))

(deftest compiler-observes-borrowed-dereferences-and-optional-unwraps
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-views-fixture))
  (let [report (discovery/prepare! 'aguafria.zig.discovery-views-fixture)
        operations (:operations report)
        dereferences (filter #(= :deref (:storage-kind %)) operations)
        unwraps (filter #(= 'aguafria.zig/unwrap (:function %)) operations)]
    (is (zero? (get-in report [:baseline :exit])))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (= #{[[:*const :i32]] [[:* :i32]]}
           (set (mapcat :signatures dereferences))))
    (is (= 4 (count dereferences)))
    (is (= #{'aguafria.zig/deref} (set (map :function dereferences))))
    (is (= [[[:optional :i32]]] (mapcat :signatures unwraps)))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers operations))))
  (let [commands (atom [])
        original shell/sh]
    (with-redefs [shell/sh (fn [& args]
                             (when (= "build-lib" (second args))
                               (swap! commands conj (vec args)))
                             (apply original args))]
      (let [constant (k/i32 12)
            pointer (k/& constant)
            variable (k/var 20 :i32)
            mutable-pointer (k/& variable)
            optional (k/as 7 [:optional :i32])]
        (is (= 12 (a/value @pointer)))
        (is (= 12 (a/value (a/deref pointer))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mutable native value"
                              (k/+= (a/deref pointer) 1)))
        (k/+= @mutable-pointer 1)
        (k/+= (a/deref mutable-pointer) 1)
        (is (= 22 (a/value variable)))
        (is (= 7 (a/value (a/unwrap optional))))))
    (is (empty? @commands) (str @commands))))

(deftest storage-free-operands-and-source-literals-are-prepared
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-literals-fixture))
  (let [report (discovery/prepare! 'aguafria.zig.discovery-literals-fixture)
        operations (:operations report)
        signatures (set (mapcat :signatures operations))]
    (is (zero? (get-in report [:baseline :exit])))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (contains? signatures [{:comptime :.ready} {:comptime :.ready}]))
    (is (contains? signatures [{:comptime-type :comptime_int} {:comptime-type :comptime_int}]))
    (is (= 5 (count (filter :literal-arguments operations))))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers operations)))))

(deftest structural-assignments-and-multiline-strings-are-observed
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-structural-values-fixture :reload))
  (let [fail! (fn [& _] (throw (ex-info "Preparation executed a body" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (discovery/prepare! 'aguafria.zig.discovery-structural-values-fixture))
        operations (:operations report)
        assignments (filter :assignment operations)
        text (first (filter #(= 'aguafria.zig/multiline-string (:function %)) operations))]
    (is (zero? (get-in report [:baseline :exit])) (:diagnostics report))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (empty? (:probe-failures report)))
    (is (= #{"+=" "="} (set (map :assignment assignments))))
    (is (some #(= [[[:optional :usize]
                    {:contextual-argument [[:optional :usize] :usize]}]]
                  (:signatures %)) assignments))
    (doseq [type [:usize :u64]]
      (is (some #(= [[[:optional type]
                      {:contextual-argument [[:optional type] :null]}]]
                    (:signatures %)) assignments)))
    (is (= '(["hello" "world"]) (seq (:literal-arguments text))))
    (is (every? #(= :prepared (:status %))
                (mapcat :handlers (conj (vec assignments) text)))
        (pr-str operations))
    (is (= "amount += 2" (emitter/emit-expr '(assign-expr "+=" amount 2))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"assignment operator"
                          (a/assign-expr "+" (k/var 1 :i32) 2)))))

(defn- structural-values-jvm [cache prepare?]
  (let [code `(do
                (require 'aguafria.keyword 'aguafria.zig 'aguafria.zig.runtime
                         'aguafria.zig.precompile 'aguafria.zig.explain)
                (aguafria.zig.runtime/configure! {:cache-dir ~cache})
                (binding [aguafria.zig.runtime/*source-only-registration?* true]
                  (require 'aguafria.zig.discovery-structural-values-fixture))
                (if ~prepare?
                  (with-redefs [aguafria.zig.runtime/invoke!
                                (fn [& _#] (throw (ex-info "Executed a body" {})))
                                aguafria.zig.runtime/invoke-with-result!
                                (fn [& _#] (throw (ex-info "Executed a body" {})))]
                    (let [report# (aguafria.zig.precompile/precompile!
                                   {:analyze ['aguafria.zig.discovery-structural-values-fixture]
                                    :report-file ~(str cache "/report.edn")})
                          analysis# (first (:analysis report#))]
                      (assert (zero? (get-in analysis# [:baseline :exit])))
                      (assert (not (:compiler-errors? analysis#)))
                      (assert (empty? (:probe-failures analysis#)))
                      (prn {:prepared true})))
                  (let [events# (atom [])
                        outputs# (binding
                                  [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                                   (let [amount# (aguafria.keyword/var 10 :i32)]
                                     (aguafria.zig/assign-expr "+=" amount# 77)
                                     (let [incremented# (aguafria.zig/value amount#)]
                                       (aguafria.zig/assign-expr "=" amount# 42)
                                       [incremented# (aguafria.zig/value amount#)
                                        (mapv (fn [type#]
                                                (let [counter# (aguafria.keyword/var 10 type#)]
                                                  (aguafria.zig/assign-expr "+=" counter# 77)
                                                  (aguafria.zig/value counter#)))
                                              [:u64 :usize :u8])
                                        (let [oldest# (aguafria.keyword/var nil [:optional :usize])
                                              index# (aguafria.keyword/usize 7)]
                                          (aguafria.zig/assign-expr "=" oldest# index#)
                                          (let [assigned# (aguafria.zig/value oldest#)]
                                            (aguafria.zig/assign-expr "=" oldest# nil)
                                            [assigned# (aguafria.zig/value oldest#)]))
                                        (mapv (fn [initial#]
                                                (let [sequence# (aguafria.keyword/var
                                                                 initial# [:optional :u64])]
                                                  (aguafria.keyword/= sequence# nil)
                                                  (aguafria.zig/value sequence#)))
                                              [11 42])
                                        (aguafria.zig/value
                                         @(aguafria.zig/multiline-string ["hello" "world"]))])))]
                    (prn {:outputs outputs# :events @events#})))
                (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Structural values JVM failed" result)))
    (edn/read-string (:out result))))

(deftest structural-values-reuse-the-bundle-after-restart
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "structural-values-" (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (structural-values-jvm cache true)
        restarted (structural-values-jvm cache false)
        events (:events restarted)
        library (:path (first (filter #(= :bundle-loaded (:event %)) events)))
        entries (when library
                  (set (keys (:entries (edn/read-string
                                        (slurp (io/file (.getParentFile (io/file library))
                                                        "manifest.edn")))))))]
    (is (:prepared prepared))
    (is (= [87 42 [87 87 87] [7 nil] [nil nil] (mapv int "hello\nworld")]
           (:outputs restarted)))
    (is (empty? (filter #(= :compiled (:event %)) events)) (pr-str events))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))) (pr-str events))
    (is (seq (filter #(= :bundle-cache-hit (:event %)) events)) (pr-str events))
    (is (empty? (filter #(= :disk-cache-hit (:event %)) events)) (pr-str events))
    (is (every? #(contains? entries
                            (artifact/key-for :bundle-entry [(:module %) (:artifact-key %)]))
                (filter :artifact-key events)) (pr-str events))))

(defn- scoped-values-jvm [cache prepare?]
  (let [code `(do
                (require 'aguafria.keyword 'aguafria.zig 'aguafria.zig.runtime
                         'aguafria.zig.precompile 'aguafria.zig.explain
                         'clojure.java.io)
                (aguafria.zig.runtime/configure! {:cache-dir ~cache})
                (binding [aguafria.zig.runtime/*source-only-registration?* true]
                  (require 'aguafria.zig.discovery-scoped-fixture))
                (if ~prepare?
                  (with-redefs [aguafria.zig.runtime/invoke!
                                (fn [& _#] (throw (ex-info "Executed a body" {})))
                                aguafria.zig.runtime/invoke-with-result!
                                (fn [& _#] (throw (ex-info "Executed a body" {})))]
                    (let [report# (aguafria.zig.precompile/precompile!
                                   {:analyze ['aguafria.zig.discovery-scoped-fixture]
                                    :report-file ~(str cache "/report.edn")})
                          analysis# (first (:analysis report#))]
                      (assert (not (:compiler-errors? analysis#)))
                      (assert (empty? (:probe-failures analysis#)))
                      (assert (every? #(contains? #{:prepared :skipped} (:status %))
                                      (:functions analysis#)))
                      (prn {:operations (:operations analysis#)})))
                  (let [forms# (with-open
                                [reader# (java.io.PushbackReader.
                                          (clojure.java.io/reader
                                           (clojure.java.io/resource
                                            "aguafria/zig/discovery_scoped_fixture.clj")))]
                                 (loop [forms# []]
                                   (let [form# (read {:eof ::eof} reader#)]
                                     (if (= ::eof form#) forms#
                                         (recur (conj forms# form#))))))
                        declarations# (into {} (map (juxt second identity)) (rest forms#))
                        events# (atom [])
                        outputs# (binding
                                  [*ns* (the-ns 'aguafria.zig.discovery-scoped-fixture)
                                   aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                                   (conj
                                    (mapv
                                     (fn [name#]
                                       (mapv (fn [input#]
                                               (aguafria.zig/value
                                                (eval (list 'let
                                                            [(symbol "input")
                                                             (list 'aguafria.keyword/i32 input#)]
                                                            (last (get declarations# name#))))))
                                             [4 14]))
                                     (mapv symbol ["read-only" "mutable" "shadowed" "propagates"]))
                                    (aguafria.zig/value
                                     (eval (last (get declarations# (symbol "closed")))))))]
                    (prn {:outputs outputs# :events @events#})))
                (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Scoped values JVM failed" result)))
    (edn/read-string (:out result))))

(deftest scoped-values-reuse-the-bundle-after-restart
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "scoped-values-" (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (scoped-values-jvm cache true)
        scopes (filter :scoped-form (:operations prepared))
        restarted (scoped-values-jvm cache false)
        events (:events restarted)
        library (:path (first (filter #(= :bundle-loaded (:event %)) events)))
        entries (when library
                  (set (keys (:entries (edn/read-string
                                        (slurp (io/file (.getParentFile (io/file library))
                                                        "manifest.edn")))))))]
    (is (= 5 (count scopes)))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers scopes)))
    (is (= [[5 15] [7 17] [6 16] [{:ok 4} {:ok 14}] 5] (:outputs restarted)))
    (is (empty? (filter #(= :compiled (:event %)) events)) (pr-str events))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))) (pr-str events))
    (is (seq (filter #(= :bundle-cache-hit (:event %)) events)) (pr-str events))
    (is (empty? (filter #(= :disk-cache-hit (:event %)) events)) (pr-str events))
    (is (every? #(contains? entries
                            (artifact/key-for :bundle-entry [(:module %) (:artifact-key %)]))
                (filter :artifact-key events)) (pr-str events))))

(deftest preparation-preserves-source-function-calling-conventions
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-function-value-fixture :reload))
  (let [target 'aguafria.zig.discovery-function-value-fixture/invoke-command
        fail! (fn [& _] (throw (ex-info "Native invocation during preparation" {})))]
    (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
      ;; This type contains a function body, not a pointer. Zig correctly has
      ;; no runtime layout for it, but valid calls using its comptime constant
      ;; must remain independently preparable afterward.
      (is (thrown? Exception
                   (runtime/precompile-type! 'aguafria.zig.discovery-function-value-fixture/Command)))
      (is (not (contains? (:jvm-type-declaration-keys
                           (get @(var-get (ns-resolve 'aguafria.zig.runtime 'registry))
                                "aguafria.zig.discovery-function-value-fixture"))
                          [:struct 'Command])))
      (is (= :prepared (:status (runtime/precompile-function! target)))))
    (is (= 42 (a/value ((resolve target) 41))))))

(deftest boolean-operator-literals-use-the-normal-call-cache
  (let [fail! (fn [& _] (throw (ex-info "Native invocation during preparation" {})))]
    (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
      (doseq [flag [false true]]
        (is (= :prepared
               (:status (jvm/precompile-call!
                         {:function 'aguafria.keyword/!
                          :args [{:comptime flag}]})))))))
  (let [commands (atom [])
        original shell/sh]
    (with-redefs [shell/sh (fn [& args]
                             (when (= "build-lib" (second args))
                               (swap! commands conj (vec args)))
                             (apply original args))]
      (is (true? (k/! false)))
      (is (false? (k/! true))))
    (is (empty? @commands) (str @commands))))

(deftest builtin-computed-parameters-retain-literal-context
  (let [failure (fn [call]
                  (try (call) ""
                       (catch Exception error
                         (or (:stderr (discovery/error-report error)) (ex-message error)))))]
    (doseq [n [0 12345]]
      (let [expected (str "integer value '" n "' represents no error")
            prepared (failure #(jvm/precompile-call!
                                {:function 'aguafria.keyword/errorFromInt
                                 :args [{:literal n :type :comptime_int}]}))
            direct (failure #(k/errorFromInt n))]
        (is (str/includes? prepared expected) prepared)
        (is (str/includes? direct expected) direct)))
    (let [typed (failure #(k/errorFromInt (k/u64 0)))]
      (is (str/includes? typed "expected type 'u16', found 'u64'") typed))))

(deftest builtin-dependent-parameters-retain-literal-context
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-builtins-fixture))
  (let [report (discovery/prepare! 'aguafria.zig.discovery-builtins-fixture)
        operations (:operations report)]
    (is (zero? (get-in report [:baseline :exit])))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers operations))))
  (let [commands (atom [])
        original shell/sh]
    (with-redefs [shell/sh (fn [& args]
                             (when (= "build-lib" (second args))
                               (swap! commands conj (vec args)))
                             (apply original args))]
      (let [number (k/u8 16)
            shift (k/u3 1)]
        (is (= 64 (a/value (k/shlExact number 2))))
        (is (= 4 (a/value (k/shrExact number 2))))
        (is (= 32 (a/value (k/shlExact number shift))))
        (is (= 8 (a/value (k/shrExact number shift))))))
    (is (empty? @commands) (str @commands)))
  ;; An explicit native type must not silently become an untyped literal merely
  ;; because that literal would fit. Zig must reject the wider shift operand.
  (let [diagnostic (try
                     (k/shlExact (k/u8 16) (k/u8 1))
                     ""
                     (catch Exception error
                       (:stderr (discovery/error-report error))))]
    (is (re-find #"expected type 'u3', found 'u8'" diagnostic))))

(deftest variadic-calls-preserve-source-strings-and-native-arguments
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-variadic-fixture))
  (let [report (discovery/prepare! 'aguafria.zig.discovery-variadic-fixture)
        prints (filter #(= 'aguafria.std.c/printf (:function %)) (:operations report))]
    (is (zero? (get-in report [:baseline :exit])))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (= #{[{:comptime ""}]
             [{:comptime "%d\n"} :i32]
             [{:comptime "%s=%d\n"} {:comptime "value"} :i32]}
           (set (mapcat :signatures prints))))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers (:operations report)))))
  (let [commands (atom [])
        original shell/sh]
    (with-redefs [shell/sh (fn [& args]
                             (when (= "build-lib" (second args))
                               (swap! commands conj (vec args)))
                             (apply original args))]
      (is (= 0 (a/value (c/printf ""))))
      (is (= 3 (a/value (c/printf "%d\n" (k/i32 12)))))
      (is (= 9 (a/value (c/printf "%s=%d\n" "value" (k/i32 42))))))
    (is (empty? @commands) (str @commands)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"variadic argument count"
                        (c/printf))))

(deftest deferred-calls-do-not-compile-invalid-standalone-calls
  (with-redefs [discovery/analyze!
                (fn [_]
                  {:operations [{:status :observed
                                 :function 'aguafria.keyword/intCast
                                 :requires-result-context? true
                                 :signatures [[:i32]]}]})
                jvm/precompile-call!
                (fn [_] (throw (ex-info "Unexpected standalone compilation" {})))]
    (let [report (discovery/prepare! 'unused)]
      (is (= [{:status :deferred :reason :result-context-required :types [:i32]}]
             (get-in report [:operations 0 :handlers])))
      (let [coverage (precompile/coverage [report])]
        (is (zero? (get-in coverage [:operations :fully-prepared])))
        (is (= 1 (get-in coverage [:deferred-calls :total])))
        (is (zero? (get-in coverage [:runtime-candidates :total])))))))

(deftest nested-representation-alternatives-expand-before-preparation
  (let [native '(aguafria.keyword/Tuple (aguafria.keyword/& [:bool]))
        descriptor {:representations
                    [native {:tuple [{:representations [:bool {:comptime false}]}]}]}]
    (is (= #{[native] [{:tuple [:bool]}] [{:tuple [{:comptime false}]}]}
           (set (:signatures (#'discovery/preparation-signatures [[descriptor]])))))
    (let [{:keys [signatures limited?]}
          (#'discovery/preparation-signatures [(vec (repeat 9 descriptor))])]
      (is (= 256 (count signatures)))
      (is limited?)
      (is (not-any? #(and (map? %) (contains? % :representations))
                    (tree-seq coll? seq signatures))))))

(deftest representation-budget-remains-an-explicit-coverage-gap
  (let [prepared (atom [])
        report (with-redefs [discovery/analyze!
                             (fn [_]
                               {:operations
                                [{:status :observed
                                  :function 'aguafria.keyword/++
                                  :signatures [[{:tuple (vec (repeat 9 {:representations [:bool {:comptime false}]}))}
                                                {:tuple []}]]}]})
                             jvm/precompile-call!
                             (fn [call]
                               (swap! prepared conj call)
                               (assoc call :status :prepared))]
                 (discovery/prepare! 'budget-only-unit-fixture))]
    (is (= 256 (count @prepared)))
    (is (= :representation-limit (-> report :operations first :handlers last :reason)))
    (is (zero? (get-in (precompile/coverage [report]) [:operations :fully-prepared])))))

(deftest tuple-operators-prepare-literal-and-native-representations
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-tuple-operators-fixture))
  (let [report (discovery/prepare! 'aguafria.zig.discovery-tuple-operators-fixture)
        operations (:operations report)]
    (is (zero? (get-in report [:baseline :exit])))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (every? #(or (= :prepared (:status %))
                     (and (= :deferred (:status %))
                          (= :result-context-required (:reason %))))
                (mapcat :handlers operations)))
    (let [commands (atom [])
          original shell/sh
          snapshot #(walk/postwalk
                     (fn [item]
                       (let [item (if (value/zig-value? item) (a/value item) item)]
                         (if (value/zig-pointer? item)
                           (.getString (value/pointer-segment item 3) 0)
                           item))) %)]
      (with-redefs [shell/sh (fn [& args]
                               (when (= "build-lib" (second args))
                                 (swap! commands conj (vec args)))
                               (apply original args))]
        (is (= [0 0 0 0 0] (snapshot (k/as (k/splat 0) [:array 5 :i32]))))
        (is (= (vec (repeat 4 [0 0 0 0 0])) (snapshot (k/as (k/splat (k/splat 0)) [:array 4 [:array 5 :i32]]))))
        (is (= [false false] (snapshot (k/as (k/splat false) [:array 2 :bool]))))
        (let [native-false (k/bool false)
              native-true (k/bool true)
              native-text (a/string-literal "\"hi\"")]
          (is (= [false false] (snapshot (k/as (k/splat native-false) [:array 2 :bool]))))
          (doseq [truth [true native-true]
                  text ["hi" native-text]
                  first-false [false native-false]
                  second-false [false native-false]]
            (let [result (k/++ [(k/u32 1234) (k/f64 12.34) truth text]
                               [first-false second-false])]
              (is (= [1234 12.34 true "hi" false false] (snapshot result)))
              (is (= 1234 (a/value (a/get result 0))))
              (is (= false (a/value (a/get result 4))))
              (is (= 6 (a/value (a/field result :len))))
              (is (= 104 (a/value (a/get-in result [:3 0]))))))))
      (is (empty? @commands) (str @commands)))))

(deftest heterogeneous-tuple-and-homogeneous-array-follow-native-rejection
  (let [error (try
                (k/++ [(k/u32 1234) (k/f64 12.34) true "hi"]
                      (k/as (k/splat false) [:array 2 :bool]))
                nil
                (catch Exception error error))]
    (is (some? error))
    (is (= "expected type 'bool', found 'u32'"
           (some-> error ex-cause ex-data :aguafria/summary)))))

(deftest slice-probes-preserve-native-index-result-context
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-slice-context-fixture))
  (let [report (discovery/prepare! 'aguafria.zig.discovery-slice-context-fixture)
        slices (filter #(= :slice (:storage-kind %)) (:operations report))
        casts (filter #(= 'aguafria.keyword/intCast (:function %)) (:operations report))]
    (is (zero? (get-in report [:baseline :exit])))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (= 2 (count slices)))
    (is (every? #(= :observed (:status %)) slices))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers slices)) (pr-str slices))
    (is (= #{{:contextual-call ['aguafria.keyword/intCast [:i32]]}}
           (set (map last (mapcat :signatures slices)))))
    (let [commands (atom [])
          original shell/sh]
      (with-redefs [shell/sh (fn [& args]
                               (when (= "build-lib" (second args))
                                 (swap! commands conj (vec args)))
                               (apply original args))]
        (with-open [array (a/array [1 2 3 4] :u8)
                    start (k/i32 1)
                    end (k/var 3 :i32)
                    slice (a/slice array 0 (k/intCast end))
                    trimmed (a/slice slice (k/intCast start) (k/intCast end))]
          (is (= [1 2 3] (mapv int (a/value slice))))
          (is (= [2 3] (mapv int (a/value trimmed))))))
      (is (empty? @commands) (pr-str @commands)))
    ;; Standalone intCast still lacks a result location; do not count it as
    ;; callable just because the enclosing native slice supplies that context.
    (is (every? #(= :result-context-required (:reason %)) (mapcat :handlers casts)))))

(deftest inspection-does-not-export-a-competing-entry-point
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-entry-fixture))
  (let [report (discovery/analyze! 'aguafria.zig.discovery-entry-fixture)]
    (is (zero? (get-in report [:baseline :exit])) (get-in report [:baseline :diagnostics]))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (some #(and (= 'aguafria.keyword/+ (:function %)) (= :observed (:status %)))
              (:operations report)))
    (is (some #{"--test-no-exec"} (:command report)))
    (is (some #{"-fno-emit-bin"} (:command report)))))

(deftest member-calls-use-the-normal-jvm-planner
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-methods-fixture))
  (let [report (binding [runtime/*compile-only?* true]
                 (discovery/prepare! 'aguafria.zig.discovery-methods-fixture))
        calls (filter :method-call? (:operations report))]
    (is (zero? (get-in report [:baseline :exit])))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (= 4 (count calls)))
    (is (= #{:twice :plus :increment} (set (map :member calls))))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers calls)) (pr-str calls))
    (let [commands (atom [])
          original shell/sh
          counter (var-get (resolve 'aguafria.zig.discovery-methods-fixture/Counter))]
      (with-redefs [shell/sh (fn [& args]
                               (when (= "build-lib" (second args))
                                 (swap! commands conj (vec args)))
                               (apply original args))]
        (with-open [constant (counter {:value 10})
                    mutable (k/var (counter {:value 20}))
                    x (k/i32 3)]
          (is (= 6 (a/value ((:twice counter) x))))
          (is (= 13 (a/value ((:plus constant) x))))
          ((:increment mutable))
          (is (= 24 (a/value ((:plus mutable) x))))))
      (is (empty? @commands) (str @commands)))))

(deftest member-preparation-preserves-source-string-arguments
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-member-literals-fixture :reload))
  (let [report (discovery/prepare! 'aguafria.zig.discovery-member-literals-fixture)
        call (first (filter :method-call? (:operations report)))
        text (var-get (resolve 'aguafria.zig.discovery-member-literals-fixture/Text))
        commands (atom [])
        original shell/sh]
    (is (zero? (get-in report [:baseline :exit])))
    (is (= :observed (:status call)))
    (is (= {:comptime "hello"} (last (first (:signatures call)))))
    (is (= [:prepared] (mapv :status (:handlers call))) (pr-str (:handlers call)))
    (with-redefs [shell/sh (fn [& arguments]
                             (when (= "build-lib" (second arguments))
                               (swap! commands conj arguments))
                             (apply original arguments))]
      (is (= 5 (a/value ((:length text) "hello")))))
    (is (empty? @commands) (pr-str @commands))))

(deftest composed-comptime-strings-share-prepared-method-adapters
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-member-literals-fixture :reload))
  (let [report (discovery/prepare! 'aguafria.zig.discovery-member-literals-fixture)
        calls (filter :method-call? (:operations report))
        concatenations (filter #(= 'aguafria.keyword/++ (:function %)) (:operations report))
        text (var-get (resolve 'aguafria.zig.discovery-member-literals-fixture/Text))
        commands (atom [])
        original shell/sh]
    (is (zero? (get-in report [:baseline :exit])))
    (is (= 3 (count calls)))
    (is (= 3 (count concatenations)))
    (is (every? #(every? (fn [handler] (= :prepared (:status handler))) (:handlers %))
                (concat calls concatenations))
        (pr-str (mapv #(select-keys % [:form :handlers]) (concat calls concatenations))))
    (is (= '(aguafria.keyword/++ "he" "llo")
           (:comptime-expression (last (first (:signatures (second calls)))))))
    (with-redefs [shell/sh (fn [& arguments]
                             (when (= "build-lib" (second arguments))
                               (swap! commands conj arguments))
                             (apply original arguments))]
      (with-open [hello (k/++ "he" "llo")
                  goodbye (k/++ (k/++ "good" " ") "bye")]
        (is (= :native (:representation (value/realize! hello))))
        (is (= '(aguafria.keyword/++ "he" "llo")
               (:comptime-expression (value/realize! hello))))
        (is (= 5 (a/value ((:length text) hello))))
        (is (= 8 (a/value ((:length text) goodbye))))))
    (is (empty? @commands) (pr-str @commands))))

(deftest vector-shifts-share-prepared-runtime-count-handlers
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-vector-shift-fixture :reload))
  (let [report (discovery/prepare! 'aguafria.zig.discovery-vector-shift-fixture)
        shift (first (filter #(= 'aguafria.keyword/>> (:function %)) (:operations report)))
        commands (atom [])
        original shell/sh]
    (is (zero? (get-in report [:baseline :exit])))
    (is (= [[[:vector 16 :u8] [:vector 16 :u3]]] (:signatures shift)))
    (is (= [:prepared] (mapv :status (:handlers shift))))
    (is (some #(= 'aguafria.zig/vector (:function %)) (:operations report)))
    (with-open [bytes (a/vector [16 32 48 64 80 96 112 128 144 160 176 192 208 224 240 255] :u8)
                counts (k/as (k/splat 4) [:vector 16 :u3])
                other-counts (k/as (k/splat 3) [:vector 16 :u3])]
      (with-redefs [shell/sh (fn [& arguments]
                               (when (= "build-lib" (second arguments))
                                 (swap! commands conj arguments))
                               (apply original arguments))]
        (is (= (vec (concat (range 1 16) [15])) (a/value (k/>> bytes counts))))
        (is (= [2 4 6 8 10 12 14 16 18 20 22 24 26 28 30 31]
               (a/value (k/>> bytes other-counts))))))
    (is (empty? @commands) (pr-str @commands))))

(deftest member-plans-canonicalize-native-type-identities
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-private-methods-fixture))
  (let [type 'aguafria.zig.discovery-private-methods-fixture/Suit
        context (the-ns 'aguafria.zig.discovery-private-methods-fixture)
        qualified (emitter/qualify-type context type)
        plan #'jvm/field-view-plan]
    (is (= (runtime/adapter-fingerprint (plan type :is-clubs [:*const type]))
           (runtime/adapter-fingerprint (plan qualified :is-clubs [:*const type]))))))

(deftest constructor-preparation-preserves-literal-versus-runtime-plans
  ;; This tests adapter selection only. Native correctness and persistence are
  ;; checked by the separate empty-cache, two-JVM regression below.
  (let [suit 'aguafria.zig.discovery-private-methods-fixture/Suit
        counter 'aguafria.zig.discovery-private-methods-fixture/Counter
        cases [[suit :.clubs]
               [:i32 42]
               [counter {:value 20}]
               [[:array 2 :u8] [1 2]]]
        operations (mapv (fn [[type initializer]]
                           {:status :observed
                            :function 'aguafria.keyword/as
                            :constructor? true
                            :literal-constructor? true
                            :constructor-value initializer
                            :signatures [[type]]})
                         cases)
        plans (atom [])
        record (fn [kind args]
                 (swap! plans conj (into [kind] args))
                 {:status :prepared})]
    (with-redefs [discovery/analyze! (fn [_] {:operations operations})
                  jvm/precompile-coercion! (fn [type] (record :runtime [type]))
                  jvm/precompile-literal-coercion! (fn [type data] (record :literal [type data]))
                  runtime/invoke! (fn [& _] (throw (ex-info "Unexpected native execution" {})))
                  shell/sh (fn [& _] (throw (ex-info "Unexpected native compilation" {})))]
      (with-redefs-fn {#'discovery/prepare-declared-functions! (constantly [])}
        #(discovery/prepare! 'aguafria.zig.discovery-private-methods-fixture)))
    (is (= [[:literal suit :.clubs]
            [:runtime :i32]
            [:runtime counter]
            [:runtime [:array 2 :u8]]]
           @plans))))

(deftest native-member-spellings-follow-the-emitter
  (let [spelling #'jvm/native-member-name]
    (is (= "is_clubs" (spelling :is-clubs)))
    (is (= "ready_q" (spelling :ready?)))
    (is (= '(aguafria.zig/string-literal "\"fn\"") (spelling :fn)))
    (is (= '(aguafria.zig/string-literal "\"really red\"")
           (spelling "@\"really red\"")))))

(deftest private-members-use-their-defining-scope
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-private-methods-fixture))
  (let [fail! (fn [& _] (throw (ex-info "Native invocation during preparation" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (binding [runtime/*compile-only?* true]
                   (discovery/prepare! 'aguafria.zig.discovery-private-methods-fixture)))
        methods (filter :method-call? (:operations report))
        source (runtime/source 'aguafria.zig.discovery-private-methods-fixture)]
    (is (zero? (get-in report [:baseline :exit])) (:diagnostics report))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (empty? (:probe-failures report)))
    (is (= #{:is-clubs :truthy :increment :plus :initial-value}
           (set (map :member methods))))
    (is (every? #(and (= :observed (:status %)) (seq (:handlers %))) methods)
        (pr-str (map #(select-keys % [:member :status :reason]) methods)))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers (:operations report)))
        (pr-str (mapcat :handlers (:operations report))))
    (is (str/includes? source "fn is_clubs("))
    (is (not (str/includes? source "pub fn is_clubs(")))
    (let [commands (atom [])
          original shell/sh
          suit-type (var-get (resolve 'aguafria.zig.discovery-private-methods-fixture/Suit))
          variant-type (var-get (resolve 'aguafria.zig.discovery-private-methods-fixture/Variant))
          counter-type (var-get (resolve 'aguafria.zig.discovery-private-methods-fixture/Counter))]
      (with-redefs [shell/sh (fn [& args]
                               (when (= "build-lib" (second args))
                                 (swap! commands conj (vec args)))
                               (apply original args))]
        (with-open [suit (k/as :.clubs suit-type)
                    variant (variant-type {:int 1})
                    counter (k/var (counter-type {:value 20}))
                    amount (k/i32 3)]
          (is (true? ((:is-clubs suit))))
          (is (true? ((:truthy variant))))
          ((:increment counter))
          (is (= 24 (a/value ((:plus counter) amount))))))
      (is (empty? @commands) (str @commands)))))

(deftest private-type-constructors-use-their-defining-scope
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-private-type-fixture))
  (let [fail! (fn [& _] (throw (ex-info "Native invocation during preparation" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (binding [runtime/*compile-only?* true]
                   (discovery/prepare! 'aguafria.zig.discovery-private-type-fixture)))
        source (runtime/source 'aguafria.zig.discovery-private-type-fixture)
        commands (atom [])
        original shell/sh]
    (is (zero? (get-in report [:baseline :exit])))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers (:operations report)))
        (pr-str (mapcat :handlers (:operations report))))
    (is (str/includes? source "fn Box("))
    (is (not (str/includes? source "pub fn Box(")))
    (with-redefs [shell/sh (fn [& args]
                             (when (= "build-lib" (second args))
                               (swap! commands conj (vec args)))
                             (apply original args))]
      (with-open [box (jvm/coerce! {:value 7}
                                   '(aguafria.zig.discovery-private-type-fixture/Box :i32))]
        (is (= 7 (a/value (:value box))))))
    (is (empty? @commands) (str @commands))))

(defn- private-member-jvm [cache prepare?]
  (let [code
        (pr-str
         `(do
            (require 'aguafria.zig.discovery 'aguafria.zig.runtime 'aguafria.zig.jvm
                     'aguafria.keyword 'aguafria.zig 'clojure.java.shell)
            (aguafria.zig.runtime/configure! {:cache-dir ~cache})
            (binding [aguafria.zig.runtime/*source-only-registration?* true]
              (require 'aguafria.zig.discovery-private-methods-fixture
                       'aguafria.zig.discovery-private-type-fixture))
            (let [commands# (atom [])
                  original# clojure.java.shell/sh]
              (with-redefs [clojure.java.shell/sh
                            (fn [& args#]
                              (when (= "build-lib" (second args#))
                                (swap! commands# conj (vec args#)))
                              (apply original# args#))]
                (if ~prepare?
                  (with-redefs [aguafria.zig.runtime/invoke!
                                (fn [& _#] (throw (ex-info "Executed a body" {})))
                                aguafria.zig.runtime/invoke-with-result!
                                (fn [& _#] (throw (ex-info "Executed a body" {})))]
                    (doseq [namespace# '[aguafria.zig.discovery-private-methods-fixture
                                         aguafria.zig.discovery-private-type-fixture]]
                      (let [report# (binding [aguafria.zig.runtime/*compile-only?* true]
                                      (aguafria.zig.discovery/prepare!
                                       namespace#))]
                        (assert (not (:compiler-errors? report#))
                                (:diagnostics report#))
                        (assert (empty? (:probe-failures report#)))
                        (assert (every? #(= :prepared (:status %))
                                        (mapcat :handlers (:operations report#)))))))
                  (let [suit-type# (var-get (resolve 'aguafria.zig.discovery-private-methods-fixture/Suit))
                        variant-type# (var-get (resolve 'aguafria.zig.discovery-private-methods-fixture/Variant))
                        counter-type# (var-get (resolve 'aguafria.zig.discovery-private-methods-fixture/Counter))]
                    (assert (ifn? (:initial-value counter-type#)))
                    (assert (= 20 (aguafria.zig/value ((:initial-value counter-type#)))))
                    (assert (not (str/includes?
                                  (aguafria.zig.runtime/source
                                   'aguafria.zig.discovery-private-methods-fixture)
                                  "pub fn initial_value(")))
                    (with-open [suit# (aguafria.keyword/as :.clubs suit-type#)
                                variant# (variant-type# {:int 1})
                                counter# (aguafria.keyword/var (counter-type# {:value 20}))
                                amount# (aguafria.keyword/i32 3)]
                      (assert (true? ((:is-clubs suit#))))
                      (assert (true? ((:truthy variant#))))
                      ((:increment counter#))
                      (assert (= 24 (aguafria.zig/value ((:plus counter#) amount#)))))
                    (with-open [box# (aguafria.zig.jvm/coerce!
                                      {:value 7}
                                      '(aguafria.zig.discovery-private-type-fixture/Box :i32))]
                      (assert (= 7 (aguafria.zig/value (:value box#))))))))
              (prn {:builds (count @commands#) :commands @commands#
                    :loaded (count (filter #(seq (:functions %))
                                           (vals @(var-get (ns-resolve 'aguafria.zig.runtime (symbol "registry"))))))}))
            (shutdown-agents)))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" code)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Private member JVM failed" result)))
    (edn/read-string (:out result))))

(deftest private-member-preparation-persists-across-jvms
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "private-members-" (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (private-member-jvm cache true)
        restarted (private-member-jvm cache false)]
    (is (pos? (:builds prepared)))
    (is (zero? (:loaded prepared)))
    (is (zero? (:builds restarted)) (pr-str restarted))))

(deftest imported-container-types-are-identified-by-zig
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-imported-fixture))
  (let [report (discovery/prepare! 'aguafria.zig.discovery-imported-fixture)
        operations (:operations report)
        signatures (mapcat :signatures operations)]
    (is (zero? (get-in report [:baseline :exit])))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (some #(some #{'aguafria.std/SemanticVersion} %) signatures))
    (is (= {:prepared 3 :partial 1}
           (frequencies (map :status (mapcat :handlers operations)))))
    (is (= [:external-type-layout]
           (keep :reason (mapcat :handlers operations))))
    (let [commands (atom [])
          original shell/sh]
      (with-redefs [shell/sh (fn [& args]
                               (when (= "build-lib" (second args))
                                 (swap! commands conj (vec args)))
                               (apply original args))]
        (with-open [v ((resolve 'aguafria.zig.discovery-imported-fixture/make-version))]
          (is (= 'aguafria.std/SemanticVersion (value/qualified-type v)))
          (is (= 1 (a/value (a/field v :major))))
          (is (= [:*const 'aguafria.std/SemanticVersion]
                 (value/qualified-type (k/& v))))))
      (is (empty? @commands) (str @commands)))))

(deftest compiler-preserves-pointer-qualifiers-and-sentinels
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-pointers-fixture))
  (let [report (discovery/prepare! 'aguafria.zig.discovery-pointers-fixture)
        operations (:operations report)
        pointer-types (set (map first (mapcat :signatures
                                              (filter #(= 'aguafria.keyword/intFromPtr (:function %)) operations))))]
    (is (zero? (get-in report [:baseline :exit])))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (contains? pointer-types [:* {:const? true :volatile? true} :i32]))
    (is (contains? pointer-types [:* {:const? true :align 1} :i32]))
    (is (contains? pointer-types [:* {:allowzero? true} :i32]))
    (is (contains? pointer-types [:sentinel-const :u8 0]))
    ;; ptrFromInt deliberately needs its enclosing cast; don't claim that
    ;; isolated result-location-dependent subexpression has been prepared.
    (is (every? #(= :prepared (:status %))
                (mapcat :handlers (filter #(= 'aguafria.keyword/intFromPtr (:function %)) operations))))
    (let [number (k/i32 42)
          pointer (k/& number)
          commands (atom [])
          original shell/sh]
      (with-redefs [shell/sh (fn [& args]
                               (when (= "build-lib" (second args))
                                 (swap! commands conj (vec args)))
                               (apply original args))]
        (doseq [type [[:* {:const? true :volatile? true} :i32]
                      [:* {:const? true :align 1} :i32]
                      [:* {:size :c :const? true} :i32]]]
          (let [qualified (k/as pointer type)]
            (is (contains? pointer-types (value/qualified-type qualified)))
            (is (pos? (a/value (k/intFromPtr qualified))))))
        (let [array (a/array [1 2] {:sentinel 0} :u8)
              sentinel (k/as (k/& array) [:sentinel-const :u8 0])]
          (is (= [:array 2 {:sentinel 0} :u8] (value/qualified-type array)))
          (is (contains? pointer-types (value/qualified-type sentinel)))
          (is (pos? (a/value (k/intFromPtr sentinel))))))
      (is (empty? @commands) (str @commands)))))

(deftest invalid-utf8-is-not-substituted-in-comptime-strings
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-bytes-fixture))
  (let [report (discovery/prepare! 'aguafria.zig.discovery-bytes-fixture)
        print-call (first (filter #(= 'aguafria.std.debug/print (:function %)) (:operations report)))]
    (is (zero? (get-in report [:baseline :exit])))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (= [[nil {:representations ['(aguafria.keyword/Tuple (aguafria.keyword/& []))
                                    {:tuple []}]}]]
           (:signatures print-call)))
    (is (= [:unsupported :unsupported] (mapv :status (:handlers print-call))))))

(deftest contextual-probes-retain-leaf-types-without-losing-result-context
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-context-fixture))
  (let [report (discovery/analyze! 'aguafria.zig.discovery-context-fixture)
        operations (:operations report)]
    (is (zero? (get-in report [:baseline :exit])))
    (is (= 1 (:inspection-attempts report)))
    (is (empty? (:probe-failures report)))
    (is (some #(and (= 'aguafria.keyword/+ (:function %))
                    (= :observed (:status %))) operations))
    (is (some #(and (:contextual-input? %)
                    (= [[:u8 :u16]] (:signatures %))) operations))))

(defn- discovery-jvm [cache prepare?]
  (let [code (pr-str
              `(do
                 (require 'aguafria.zig.precompile 'aguafria.zig.runtime
                          'aguafria.keyword 'aguafria.zig 'clojure.java.shell)
                 (require 'aguafria.std 'aguafria.std.testing 'aguafria.std.debug 'aguafria.std.c)
                 (aguafria.zig.runtime/configure! {:cache-dir ~cache})
                 (let [commands# (atom [])
                       original# clojure.java.shell/sh]
                   (with-redefs [clojure.java.shell/sh
                                 (fn [& args#]
                                   (when (= "build-lib" (second args#))
                                     (swap! commands# conj (vec args#)))
                                   (apply original# args#))]
                     (if ~prepare?
                       (with-redefs [aguafria.zig.runtime/invoke!
                                     (fn [& _#] (throw (ex-info "Executed a body" {})))]
                         (aguafria.zig.precompile/precompile!
                          {:analyze ['aguafria.zig.discovery-fixture
                                     'aguafria.zig.discovery-tuple-fixture
                                     'aguafria.zig.discovery-nominal-fixture
                                     'aguafria.zig.discovery-literals-fixture
                                     'aguafria.zig.discovery-views-fixture
                                     'aguafria.zig.discovery-builtins-fixture
                                     'aguafria.zig.discovery-variadic-fixture
                                     'aguafria.zig.discovery-pointers-fixture
                                     'aguafria.zig.discovery-imported-fixture
                                     'aguafria.zig.discovery-methods-fixture
                                     'aguafria.zig.discovery-generic-type-fixture
                                     'aguafria.zig.discovery-tuple-operators-fixture
                                     'aguafria.zig.discovery-core-operator-fixture
                                     'aguafria.zig.discovery-member-literals-fixture
                                     'aguafria.zig.discovery-vector-shift-fixture
                                     'aguafria.zig.discovery-nested-fields-fixture]
                           :report-file ~(str cache "/report.edn")}))
                       (let [x# (aguafria.keyword/i32 10)
                             y# (aguafria.keyword/i32 20)]
                         (require 'aguafria.zig.discovery-imported-fixture
                                  'aguafria.zig.discovery-generic-type-fixture
                                  'aguafria.zig.discovery-methods-fixture
                                  'aguafria.zig.discovery-core-operator-fixture
                                  'aguafria.zig.discovery-member-literals-fixture
                                  'aguafria.zig.discovery-vector-shift-fixture
                                  'aguafria.zig.discovery-nested-fields-fixture)
                         (with-open [exchange# ((resolve 'aguafria.zig.discovery-nested-fields-fixture/make-exchange))]
                           (assert (= 7 (aguafria.zig/value (:actor (:request (:result exchange#)))))))
                         (let [text# (var-get (resolve 'aguafria.zig.discovery-member-literals-fixture/Text))]
                           (with-open [hello# (aguafria.keyword/++ "he" "llo")
                                       goodbye# (aguafria.keyword/++ (aguafria.keyword/++ "good" " ") "bye")]
                             (assert (= 5 (aguafria.zig/value ((:length text#) hello#))))
                             (assert (= 8 (aguafria.zig/value ((:length text#) goodbye#))))))
                         (with-open [bytes# (aguafria.zig/vector [16 32 48 64 80 96 112 128 144 160 176 192 208 224 240 255] :u8)
                                     counts# (aguafria.keyword/as (aguafria.keyword/splat 4) [:vector 16 :u3])]
                           (assert (= (vec (concat (range 1 16) [15]))
                                      (aguafria.zig/value (aguafria.keyword/>> bytes# counts#)))))
                         (let [left# (aguafria.keyword/f32 8)
                               right# (aguafria.keyword/f32 2)]
                           (assert (= 10.0 (aguafria.zig/value
                                            (aguafria.keyword/+ left# right#))))
                           (assert (= 16.0 (aguafria.zig/value
                                            (aguafria.keyword/* left# right#))))
                           (assert (= 4.0 (aguafria.zig/value
                                           (aguafria.keyword// left# right#)))))
                         (doseq [[type# number#] [[:i32 42] [:u16 7] [:i32 15]]]
                           (let [box-type# ((resolve 'aguafria.zig.discovery-generic-type-fixture/Box) type#)
                                 box# (aguafria.zig/init {:value number#} box-type#)]
                             (assert (= number# (aguafria.zig/value (:value box#))))))
                         (let [counter# (var-get (resolve 'aguafria.zig.discovery-methods-fixture/Counter))]
                           (with-open [constant# (counter# {:value 10})
                                       mutable# (aguafria.keyword/var (counter# {:value 20}))
                                       increment# (aguafria.keyword/i32 3)]
                             (assert (= 6 (aguafria.zig/value ((:twice counter#) increment#))))
                             (assert (= 13 (aguafria.zig/value ((:plus constant#) increment#))))
                             ((:increment mutable#))
                             (assert (= 24 (aguafria.zig/value ((:plus mutable#) increment#))))))
                         (with-open [version# ((resolve 'aguafria.zig.discovery-imported-fixture/make-version))]
                           (assert (= 1 (aguafria.zig/value (aguafria.zig/field version# :major))))
                           (assert (= [:*const 'aguafria.std/SemanticVersion]
                                      (aguafria.zig.value/qualified-type (aguafria.keyword/& version#)))))
                         (assert (= 30 (aguafria.zig/value (aguafria.keyword/+ x# y#))))
                         (assert (= [0 0 0 0 0] (mapv aguafria.zig/value (aguafria.keyword/as (aguafria.keyword/splat 0) [:array 5 :i32]))))
                         (assert (= (vec (repeat 4 [0 0 0 0 0]))
                                    (mapv (fn [row#] (mapv aguafria.zig/value row#))
                                          (aguafria.keyword/as (aguafria.keyword/splat (aguafria.keyword/splat 0)) [:array 4 [:array 5 :i32]]))))
                         (assert (= [false false] (aguafria.zig/value (aguafria.keyword/as (aguafria.keyword/splat false) [:array 2 :bool]))))
                         (let [native-false# (aguafria.keyword/bool false)
                               native-true# (aguafria.keyword/bool true)
                               native-text# (aguafria.zig/string-literal "\"hi\"")]
                           (doseq [truth# [true native-true#]
                                   text# ["hi" native-text#]
                                   first-false# [false native-false#]
                                   second-false# [false native-false#]]
                             (let [result# (aguafria.keyword/++
                                            [(aguafria.keyword/u32 1234) (aguafria.keyword/f64 12.34) truth# text#]
                                            [first-false# second-false#])]
                               (assert (= 6 (count result#)))
                               (assert (= 1234 (aguafria.zig/value (nth result# 0))))
                               (assert (= false (aguafria.zig/value (nth result# 4))))
                               (assert (= 1234 (aguafria.zig/value (aguafria.zig/get result# 0))))
                               (assert (= false (aguafria.zig/value (aguafria.zig/get result# 4))))
                               (assert (= 6 (aguafria.zig/value (aguafria.zig/field result# :len))))
                               (assert (= 104 (aguafria.zig/value (aguafria.zig/get-in result# [:3 0])))))))
                         (assert (= 0 (aguafria.zig/value (aguafria.std.c/printf ""))))
                         (assert (= 3 (aguafria.zig/value (aguafria.std.c/printf "%d\n" (aguafria.keyword/i32 12)))))
                         (assert (= 9 (aguafria.zig/value (aguafria.std.c/printf "%s=%d\n" "value" (aguafria.keyword/i32 42)))))
                         (let [pointer# (aguafria.keyword/& x#)]
                           (doseq [type# [[:* {:const? true :volatile? true} :i32]
                                          [:* {:const? true :align 1} :i32]
                                          [:* {:size :c :const? true} :i32]]]
                             (assert (pos? (aguafria.zig/value
                                            (aguafria.keyword/intFromPtr
                                             (aguafria.keyword/as pointer# type#)))))))
                         (let [array# (aguafria.zig/array [1 2] {:sentinel 0} :u8)
                               pointer# (aguafria.keyword/as (aguafria.keyword/& array#) [:sentinel-const :u8 0])]
                           (assert (= [:array 2 {:sentinel 0} :u8]
                                      (aguafria.zig.value/qualified-type array#)))
                           (assert (pos? (aguafria.zig/value (aguafria.keyword/intFromPtr pointer#)))))
                         (let [number# (aguafria.keyword/u8 16)
                               shift# (aguafria.keyword/u3 1)]
                           (assert (= 64 (aguafria.zig/value (aguafria.keyword/shlExact number# 2))))
                           (assert (= 4 (aguafria.zig/value (aguafria.keyword/shrExact number# 2))))
                           (assert (= 32 (aguafria.zig/value (aguafria.keyword/shlExact number# shift#))))
                           (assert (= 8 (aguafria.zig/value (aguafria.keyword/shrExact number# shift#)))))
                         (let [pointer# (aguafria.keyword/& x#)
                               variable# (aguafria.keyword/var 20 :i32)
                               mutable-pointer# (aguafria.keyword/& variable#)
                               optional# (aguafria.keyword/as 7 [:optional :i32])]
                           (assert (= 10 (aguafria.zig/value @pointer#)))
                           (assert (= 10 (aguafria.zig/value (aguafria.zig/deref pointer#))))
                           (aguafria.keyword/+= @mutable-pointer# 1)
                           (aguafria.keyword/+= (aguafria.zig/deref mutable-pointer#) 1)
                           (assert (= 22 (aguafria.zig/value variable#)))
                           (assert (= 7 (aguafria.zig/value (aguafria.zig/unwrap optional#)))))
                         (with-open [result# (aguafria.keyword/+ x# y#)]
                           (assert (= 30 (aguafria.zig/value result#))))
                         (assert (= "number=42 literal=7 boolean=true\nquoted=\"1.25\"\tλ\n"
                                    (let [writer# (java.io.StringWriter.)]
                                      (binding [*err* writer#]
                                        (aguafria.std.debug/print
                                         "number={} literal={} boolean={}\n"
                                         [(aguafria.keyword/i32 42) 7 true])
                                        (aguafria.std.debug/print
                                         "quoted=\"{}\"\tλ\n" [(aguafria.keyword/f32 1.25)]))
                                      (str writer#))))
                         (assert (aguafria.zig/value
                                  (aguafria.keyword/== :i32 :i32)))
                         (assert (= {:ok nil}
                                    (aguafria.zig/value
                                     (aguafria.std.testing/expectEqual
                                      :comptime_int (aguafria.keyword/TypeOf (aguafria.keyword/+ 1 2))))))
                         (assert (aguafria.zig/value (aguafria.keyword/== :.ready :.ready)))
                         (assert (= "literal=.ready\n"
                                    (let [writer# (java.io.StringWriter.)]
                                      (binding [*err* writer#]
                                        (aguafria.std.debug/print "literal={any}\n" [:.ready]))
                                      (str writer#))))
                         (assert (= 493 (aguafria.zig/value (aguafria.zig/number-literal "0o755"))))
                         (assert (= 101 (aguafria.zig/value (aguafria.zig/char-literal "'\\x65'"))))
                         (assert (= "with space" (aguafria.zig/enum-literal ".@\"with space\"")))
                         (assert (= "ExampleFailure" (:name (aguafria.zig/error-value :ExampleFailure))))
                         (with-open [literal# (aguafria.zig/string-literal "\"h\\x65llo\"")]
                           (assert (= "hello"
                                      (.getString
                                       (aguafria.zig.value/pointer-segment (aguafria.zig/value literal#) 6) 0))))
                         (assert (= 17 (aguafria.zig/value (aguafria.keyword/+ x# 7))))
                         (assert (= 63 (aguafria.zig/value (aguafria.keyword/+ 31 32))))
                         (assert (= 1099511627776 (aguafria.zig/value (aguafria.keyword/<< 1 40))))
                         (assert (= {:ok nil}
                                    (aguafria.zig/value
                                     (aguafria.std.testing/expectEqual 1.2 (aguafria.keyword/f32 1.2)))))
                         (assert (= (float (/ 7.0 3.0))
                                    (float (aguafria.zig/value
                                            (aguafria.keyword/f32
                                             (aguafria.keyword// 7.0 3.0))))))
                         (assert (= [1 2]
                                    (aguafria.zig/value
                                     (aguafria.keyword/as
                                      (aguafria.zig/array [1 2] :i32)
                                      [:vector 2 :i32]))))
                         (let [array# (aguafria.zig/array [1 2] :i32)]
                           (assert (= 2 (aguafria.zig/value (:len array#))))
                           (assert (= 1 (aguafria.zig/value (aguafria.zig/get array# 0)))))
                         (let [array# (aguafria.keyword/var (aguafria.zig/array [1 2] :i32))]
                           (aguafria.keyword/+= (aguafria.zig/get array# 0) 1)
                           (assert (= 2 (aguafria.zig/value (aguafria.zig/get array# 0)))))
                         (let [array# (aguafria.keyword/var (aguafria.zig/array [1 2] :i32))
                               start# (aguafria.keyword/var 0 :usize)
                               slice# (aguafria.zig/slice array# start# 2)]
                           (aguafria.keyword/& start#)
                           (aguafria.keyword/+= (aguafria.zig/get slice# 1) 1)
                           (assert (= 3 (aguafria.zig/value (aguafria.zig/get array# 1)))))
                         (let [optional# (aguafria.keyword/as nil [:optional :i32])]
                           (assert (aguafria.zig/value (aguafria.keyword/== optional# nil))))
                         (let [array# (aguafria.zig/init [3 4] [:array 2 :u16])]
                           (aguafria.zig/slice array# 0 1))
                         (require 'aguafria.zig.discovery-fixture
                                  'aguafria.zig.discovery-nominal-fixture)
                         (assert (= 42 (aguafria.zig/value
                                        ((resolve 'aguafria.zig.discovery-fixture/add-literal) 35))))
                         (let [point# ((resolve 'aguafria.zig.discovery-fixture/Point) {:x 1 :y 2})]
                           (assert (= 1 (aguafria.zig/value (:x point#)))))
                         (let [tag# (var-get (resolve 'aguafria.zig.discovery-nominal-fixture/Tag))
                               payload# ((resolve 'aguafria.zig.discovery-nominal-fixture/Payload) {:integer 42})
                               raw# ((resolve 'aguafria.zig.discovery-nominal-fixture/Raw) {:integer 12})
                               counter# (var-get (resolve 'aguafria.zig.discovery-nominal-fixture/Counter))]
                           (assert (= "integer" (aguafria.zig/value (:integer tag#))))
                           (assert (= 42 (aguafria.zig/value (:integer payload#))))
                           (assert (= 12 (aguafria.zig/value (:integer raw#))))
                           (assert (= 10 (aguafria.zig/value (:initial counter#))))
                           (assert (= 10 (aguafria.zig/value (:value counter#))))
                           (aguafria.keyword/+= (:value counter#) 1)
                           (assert (= 11 (aguafria.zig/value (:value counter#))))))))
                   (prn {:builds (count @commands#) :commands @commands#
                         :libraries (into #{}
                                          (comp (filter #(.isFile %))
                                                (filter #(some (fn [suffix#]
                                                                 (str/ends-with? (.getName %) suffix#))
                                                               [".dylib" ".so" ".dll"]))
                                                (map str))
                                          (file-seq (io/file ~cache)))
                         :loaded (count (filter #(seq (:functions %))
                                                (vals @(var-get (ns-resolve 'aguafria.zig.runtime (symbol "registry"))))))}))
                 (shutdown-agents)
                 (flush)
                 (System/exit 0)))
        output (Files/createTempFile "aguafria-discovery-" ".log"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        ;; Cold preparation covers hundreds of adapters. Keep it bounded, but
        ;; allow compiler contention without weakening the zero-build assertion.
        timeout-seconds (if prepare? 600 300)
        process (.start (doto (ProcessBuilder.
                               ^java.util.List
                               [(str (System/getProperty "java.home") "/bin/java")
                                "--enable-native-access=ALL-UNNAMED"
                                "-cp" (System/getProperty "java.class.path")
                                "clojure.main" "-e" code])
                          (.redirectErrorStream true)
                          (.redirectOutput (.toFile output))))]
    (when-not (.waitFor process timeout-seconds TimeUnit/SECONDS)
      (.destroyForcibly process)
      (throw (ex-info "Discovery test JVM timed out"
                      {:log (str output)
                       :phase (if prepare? :prepare :invoke)
                       :timeout-seconds timeout-seconds})))
    (let [text (slurp (.toFile output))]
      (when-not (zero? (.exitValue process))
        (throw (ex-info "Discovery test JVM failed" {:output text})))
      (edn/read-string (last (str/split-lines text))))))

(deftest automatically-discovered-handlers-survive-restart
  (let [parent (io/file ".aguafria/precompile-tests")
        _ (.mkdirs parent)
        cache (str (Files/createTempDirectory
                    (.toPath (.getAbsoluteFile parent)) "discovery-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (discovery-jvm cache true)
        restarted (discovery-jvm cache false)]
    (is (pos? (:builds prepared)))
    (is (zero? (:loaded prepared)) (str prepared))
    (is (zero? (:builds restarted)) (str restarted))
    (is (seq (:libraries prepared)) "Preparation must report its actual native libraries")
    (is (= (:libraries prepared) (:libraries restarted))
        (str "Ordinary loading and invocation must not create additional native libraries: "
             (remove (:libraries prepared) (:libraries restarted))))))

(deftest compile-only-boundary-rejects-native-execution
  (binding [runtime/*compile-only?* true]
    (doseq [call [#(runtime/invoke! 'aguafria.not-loaded/function [])
                  #(runtime/invoke-version! 'aguafria.not-loaded/function "none" [])
                  #(runtime/run-test! 'aguafria.not-loaded 'example)]]
      (is (= :compile-only
             (try (call) nil
                  (catch clojure.lang.ExceptionInfo error
                    (:aguafria/phase (ex-data error)))))))))
