(ns aguafria.zig.discovery-test
  (:require [aguafria.keyword :as k]
            [aguafria.std.c :as c]
            [aguafria.zig :as az]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.emitter :as emitter]
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

(deftest adapter-identities-ignore-map-order-but-preserve-type-information
  (let [left [:* (array-map :const? true :align 1) :i32]
        right [:* (array-map :align 1 :const? true) :i32]]
    (is (= (runtime/adapter-fingerprint left) (runtime/adapter-fingerprint right)))
    (is (not= (runtime/adapter-fingerprint left)
              (runtime/adapter-fingerprint [:* {:const? true :align 2} :i32])))
    (is (not= (runtime/adapter-fingerprint (with-meta 'x {:zig/type :i32}))
              (runtime/adapter-fingerprint (with-meta 'x {:zig/type :u32}))))))

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
          (is (= 30 (az/value (k/+ x y))))
          (is (= 17 (az/value (k/+ x 7))))
          (is (= 63 (az/value (k/+ 31 32))))
          (is (= (float (/ 7.0 3.0)) (float (az/value (k/f32 (k// 7.0 3.0))))))
          (is (= [1 2] (az/value (k/as (az/array [1 2] :i32) [:vector 2 :i32]))))
          (let [array (az/array [1 2] :i32)]
            (is (= 2 (az/value (:len array))))
            (is (= 1 (az/value (az/get array 0)))))
          (let [array (k/var (az/array [1 2] :i32))]
            (k/+= (az/get array 0) 1)
            (is (= 2 (az/value (az/get array 0)))))))
      (is (empty? @commands) (str @commands)))))

(deftest namespace-errors-do-not-abort-the-inventory
  (let [report (precompile/precompile!
                {:analyze ['aguafria.no-such-namespace 'aguafria.zig.discovery-fixture]})]
    (is (= 2 (count (:analysis report))))
    (is (= :load-failed (get-in report [:analysis 0 :status])))
    (is (= :zig-compiler (get-in report [:analysis 1 :basis])))))

(deftest unused-concrete-functions-are-prepared-without-execution
  (let [fail! (fn [& _] (throw (ex-info "Native invocation during discovery" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (first (:analysis
                         (precompile/precompile!
                          {:analyze ['aguafria.zig.discovery-fixture]}))))
        functions (into {} (map (juxt :function identity)) (:functions report))
        function (ns-resolve 'aguafria.zig.discovery-fixture 'add-literal)
        commands (atom [])
        original shell/sh]
    ;; No other declaration calls add-literal; observing calls alone misses it.
    (is (= :prepared (:status (functions 'aguafria.zig.discovery-fixture/add-literal))))
    (is (= :specialization (:reason (functions 'aguafria.zig.discovery-fixture/generic-add))))
    (is (= :test-runner (:reason (functions 'aguafria.zig.discovery-fixture/do-not-execute))))
    (is (= 4 (count functions)))
    (with-redefs [shell/sh (fn [& arguments]
                             (swap! commands conj (take 2 arguments))
                             (apply original arguments))]
      (is (= 42 (az/value (function 35)))))
    (is (empty? @commands) (str @commands))))

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
    (is (= [:computed-type-literal-construction]
           (mapv :reason (mapcat :handlers (remove :literal-constructor? constructors)))))
    (with-redefs [shell/sh (fn [& arguments]
                             (swap! commands conj (take 2 arguments))
                             (apply original arguments))]
      (doseq [[type number] [[:i32 42] [:u16 7] [:i32 15]]]
        (let [box-type ((ns-resolve 'aguafria.zig.discovery-generic-type-fixture 'Box) type)
              box (az/init {:value number} box-type)]
          (is (= number (az/value (:value box)))))))
    (is (empty? @commands) (str @commands))))

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
          (is (= items (az/value native)))
          (is (= items (az/value array)))))
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
      (is (= original (az/value native)))
      (k/= mutable changed)
      (is (= changed (az/value mutable)))
      (is (= original (az/value native))))))

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
    (is (seq (filter #(= 'clojure.core/deref (:function %)) dereferences)))
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
        (is (= 12 (az/value @pointer)))
        (is (= 12 (az/value (az/deref pointer))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mutable native value"
                              (k/+= (az/deref pointer) 1)))
        (k/+= @mutable-pointer 1)
        (k/+= (az/deref mutable-pointer) 1)
        (is (= 22 (az/value variable)))
        (is (= 7 (az/value (az/unwrap optional))))))
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
    (is (= 42 (az/value ((resolve target) 41))))))

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
        (is (= 64 (az/value (k/shlExact number 2))))
        (is (= 4 (az/value (k/shrExact number 2))))
        (is (= 32 (az/value (k/shlExact number shift))))
        (is (= 8 (az/value (k/shrExact number shift))))))
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
      (is (= 0 (az/value (c/printf ""))))
      (is (= 3 (az/value (c/printf "%d\n" (k/i32 12)))))
      (is (= 9 (az/value (c/printf "%s=%d\n" "value" (k/i32 42))))))
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
    (is (every? #(= :prepared (:status %)) (mapcat :handlers operations)))
    (let [commands (atom [])
          original shell/sh
          snapshot #(walk/postwalk
                     (fn [item]
                       (let [item (if (value/zig-value? item) (az/value item) item)]
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
        (is (= [1234 12.34 true "hi" false false]
               (snapshot (k/++ [(k/u32 1234) (k/f64 12.34) true "hi"] (k/as (k/splat false) [:array 2 :bool])))))
        (let [native-false (k/bool false)
              native-true (k/bool true)
              native-text (az/string-literal "\"hi\"")]
          (is (= [false false] (snapshot (k/as (k/splat native-false) [:array 2 :bool]))))
          (doseq [truth [true native-true]
                  text ["hi" native-text]
                  first-false [false native-false]
                  second-false [false native-false]]
            (let [result (k/++ [(k/u32 1234) (k/f64 12.34) truth text]
                               [first-false second-false])]
              (is (= [1234 12.34 true "hi" false false] (snapshot result)))
              (is (= 1234 (az/value (az/get result 0))))
              (is (= false (az/value (az/get result 4))))
              (is (= 6 (az/value (az/field result :len))))
              (is (= 104 (az/value (az/get-in result [:3 0]))))))))
      (is (empty? @commands) (str @commands)))))

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
    (is (= #{:usize} (set (map last (mapcat :signatures slices)))))
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
          (is (= 6 (az/value ((:twice counter) x))))
          (is (= 13 (az/value ((:plus constant) x))))
          ((:increment mutable))
          (is (= 24 (az/value ((:plus mutable) x))))))
      (is (empty? @commands) (str @commands)))))

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
    (is (= #{:is-clubs :truthy :increment :plus} (set (map :member methods))))
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
          (is (= 24 (az/value ((:plus counter) amount))))))
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
        (is (= 7 (az/value (:value box))))))
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
                        (assert (every? #(= :prepared (:status %))
                                        (mapcat :handlers (:operations report#)))))))
                  (let [suit-type# (var-get (resolve 'aguafria.zig.discovery-private-methods-fixture/Suit))
                        variant-type# (var-get (resolve 'aguafria.zig.discovery-private-methods-fixture/Variant))
                        counter-type# (var-get (resolve 'aguafria.zig.discovery-private-methods-fixture/Counter))]
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
          (is (= 1 (az/value (az/field v :major))))
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
            (is (pos? (az/value (k/intFromPtr qualified))))))
        (let [array (az/array [1 2] {:sentinel 0} :u8)
              sentinel (k/as (k/& array) [:sentinel-const :u8 0])]
          (is (= [:array 2 {:sentinel 0} :u8] (value/qualified-type array)))
          (is (contains? pointer-types (value/qualified-type sentinel)))
          (is (pos? (az/value (k/intFromPtr sentinel))))))
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
                                     'aguafria.zig.discovery-tuple-operators-fixture]
                           :report-file ~(str cache "/report.edn")}))
                       (let [x# (aguafria.keyword/i32 10)
                             y# (aguafria.keyword/i32 20)]
                         (binding [aguafria.zig.runtime/*source-only-registration?* true]
                           (require 'aguafria.zig.discovery-imported-fixture
                                    'aguafria.zig.discovery-generic-type-fixture
                                    'aguafria.zig.discovery-methods-fixture))
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
                         (binding [aguafria.zig.runtime/*source-only-registration?* true]
                           (require 'aguafria.zig.discovery-fixture
                                    'aguafria.zig.discovery-nominal-fixture))
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
    (is (zero? (:builds restarted)) (str restarted))))

(deftest compile-only-boundary-rejects-native-execution
  (binding [runtime/*compile-only?* true]
    (doseq [call [#(runtime/invoke! 'aguafria.not-loaded/function [])
                  #(runtime/invoke-version! 'aguafria.not-loaded/function "none" [])
                  #(runtime/run-test! 'aguafria.not-loaded 'example)]]
      (is (= :compile-only
             (try (call) nil
                  (catch clojure.lang.ExceptionInfo error
                    (:aguafria/phase (ex-data error)))))))))
