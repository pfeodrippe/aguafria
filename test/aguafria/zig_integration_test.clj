(ns aguafria.zig-integration-test
  (:require [aguafria.keyword :as ak]
            [aguafria.std :as zig-std]
            [aguafria.zig :as az]
            [aguafria.zig.host :as host]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; Requiring this namespace exercises the opt-in parallel compiler. Each macro
;; queues a complete immutable snapshot; the first invocation awaits the newest.
(az/configure! {:async? true
                :modules {"extra_math" "test/fixtures/extra_math.zig"}})

;; The command-line test alias supplies a stable suffix so an unchanged suite
;; in a fresh JVM can reuse exact content-addressed native artifacts. An nREPL
;; rerun gets a fresh suffix and therefore cannot inherit a prior test module.
;; Source changes produce a new artifact hash without a suffix bump.
(def ^:private fixture-suffix
  (or (System/getProperty "aguafria.test.fixture-suffix")
      (str (random-uuid))))

(az/defimport std "std" [])

(az/defimport extra-math "extra_math" [quadruple])

(az/defstruct Point
  [[:x {:doc "Horizontal coordinate"} :i32]
   [:y :i32]])

(az/defconst multiplier :i32 3)

(az/defn ^{:export false :public true} sum-point :- :i32
  [point :- Point]
  (+ (field point x) (field point y)))

(az/defn constructed-point-sum :- :i32
  []
  (sum-point (Point {:y 5 :x 4})))

(az/defn base
  "Increment an integer in Zig."
  :- :i32
  [x :- :i32]
  (+ x 1))

(az/defn external-quadruple :- :i32
  [x :- :i32]
  (extra-math/quadruple x))

(az/defn ^{:export false :public true} simd-lane-sum :- :i32
  [values :- (ak/Vector 4 :i32)]
  (ak/reduce :.Add values))

(az/defn keyword-int-cast :- :i32
  [value :- :i64]
  (ak/intCast value))

(az/defn reader-safe-bit-xor :- :u32
  [left :- :u32 right :- :u32]
  (ak/bit-xor left right))

(az/defn simd-sum4 :- :i32
  [a :- :i32 b :- :i32 c :- :i32 d :- :i32]
  (simd-lane-sum [a b c d]))

(az/defn composed :- :i32
  [x :- :i32]
  (* (base x) multiplier))

(az/defn ^{:export false :public true} zig-only-base :- :i32
  [x :- :i32]
  (+ x 4))

(az/defn zig-only-composed :- :i32
  [x :- :i32]
  (* (zig-only-base x) 2))

(az/defn ^{:export false :public true} comptime-plus-one :- :u32
  [x :- :u32]
  (+ x 1))

(az/defconst comptime-answer :u32 (comptime-plus-one 41))

(az/defn comptime-answer-value :- :u32
  []
  comptime-answer)

(az/defn sum-to :- :i32
  [n :- :i32]
  (var total :i32 0)
  (var i :i32 0)
  (while (< i n)
    (+= total i)
    (+= i 1))
  total)

(az/defn abs-i32 :- :i32
  [x :- :i32]
  (if (< x 0)
    (- x)
    x))

(az/defn ^{:export false :public true} main :- :void
  [])

(defn- declaration-stat
  [name]
  (some #(when (= name (:name %)) %)
        (:declarations (az/stats 'aguafria.zig-integration-test))))

(defn- capture-declaration
  [target-ns form]
  (let [captured (atom [])]
    (binding [*ns* target-ns
              runtime/*registration-batch* captured]
      (eval form))
    (or (first @captured)
        (throw (ex-info "Aguafria form captured no declaration"
                        {:namespace (ns-name target-ns) :form form})))))

(deftest native-process-main-host-test
  (testing "a std.process.Init main runs in the JVM and shares live native state"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.process-host-" fixture-suffix))
          test-ns (create-ns test-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (alias 'std-process 'aguafria.std.process)
          (eval '(az/defvar observed :u32 0))
          (eval '(az/defn observed-value :- :u32 [] observed))
          (eval '(az/defn main
                   {:zig/qualifiers "!" :attrs #{:public}}
                   :- :void
                   [[process-init std-process/Init]]
                   (set! _ process-init)
                   (set! observed 42))))
        (let [main-var (ns-resolve test-ns 'main)
              stack-size (* 20 1024 1024)
              handle (host/start! main-var ["argument"]
                                  {:argv0 "fixture"
                                   :stack-size-bytes stack-size})
              result (host/await! handle)
              final-info (host/info handle)]
          (is (= 0 (:exit-code result)))
          (is (= 42 ((ns-resolve test-ns 'observed-value))))
          (is (= :finished (:status final-info)))
          (is (= stack-size (:stack-size-bytes final-info)))
          (is (false? (:active? final-info))))
        (finally
          (az/configure! old-config))))))

(deftest non-exported-var-is-lazily-callable-and-cached-test
  (testing "an ordinary Clojure Var materializes one development trampoline"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.lazy-var-" fixture-suffix))
          test-ns (create-ns test-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defn twice
                   {:attrs #{:public :implicit-return}}
                   :- :i32
                   [value :- :i32]
                   (* value 2))))
        (let [twice (ns-resolve test-ns 'twice)
              before (:requested-generation (az/module-info test-symbol))]
          (is (var? twice))
          (is (= 42 (twice 21)))
          (let [after-first (:requested-generation
                             (az/module-info test-symbol))]
            (is (= (inc before) after-first))
            (is (= 42 (twice 21)))
            (is (= after-first
                   (:requested-generation (az/module-info test-symbol))))))
        (finally
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest native-zig-values-round-trip-and-print-as-values-test
  (testing "a non-JVM-shaped integer retains native bytes across a Zig call"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.native-value-" fixture-suffix))
          test-ns (create-ns test-symbol)
          input (atom nil)
          output (atom nil)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defconst wide :u56 283686952306183))
          (eval '(az/defn identity-wide
                   {:attrs #{:public :implicit-return}}
                   :- :u56
                   [value :- :u56]
                   value))
          (eval '(az/defn identity-u64
                   {:attrs #{:public :implicit-return}}
                   :- :u64
                   [value :- :u64]
                   value)))
        (reset! input (var-get (ns-resolve test-ns 'wide)))
        (reset! output ((ns-resolve test-ns 'identity-wide) @input))
        (is (az/zig-value? @input))
        (is (az/zig-value? @output))
        (is (= "283686952306183" (pr-str @input)))
        (is (= "283686952306183\n"
               (with-out-str (pprint/pprint @output))))
        (is (= [7 6 5 4 3 2 1 0] (az/native-bytes @input)))
        (is (= (az/native-bytes @input) (az/native-bytes @output)))
        (is (= {:size 8 :alignment 8 :type :u56}
               (select-keys (az/value-info @output)
                            [:size :alignment :type])))
        (is (= 18446744073709551615N
               ((ns-resolve test-ns 'identity-u64)
                18446744073709551615N)))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"out of range"
             ((ns-resolve test-ns 'identity-u64)
              18446744073709551616N)))
        (az/close! @input)
        (az/close! @input)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Zig value is closed"
                              (az/value @input)))
        (finally
          (doseq [value [@output @input]
                  :when (az/zig-value? value)]
            (.close ^java.lang.AutoCloseable value))
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest defvar-root-is-a-live-native-value-test
  (testing "a defvar Var exposes changing native state rather than metadata"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.native-state-" fixture-suffix))
          test-ns (create-ns test-symbol)
          state-value (atom nil)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defvar live-count :u24 66051))
          (eval '(az/defn bump :- :void [] (+= live-count 1))))
        (reset! state-value (var-get (ns-resolve test-ns 'live-count)))
        (is (az/zig-value? @state-value))
        (is (= 66051 (az/value @state-value)))
        ((ns-resolve test-ns 'bump))
        (is (= 66052 (az/value @state-value)))
        (is (= "66052" (pr-str @state-value)))
        (is (= 1000 (az/set-value! @state-value 1000)))
        ((ns-resolve test-ns 'bump))
        (is (= 1001 (az/value @state-value)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"out of range"
                              (az/set-value! @state-value 16777216)))
        (finally
          (az/close! @state-value)
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest special-type-defvars-remain-mutable-across-reload-test
  (testing "optional, error-union, and slice state mutate in native memory and survive reload"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.special-state-" fixture-suffix))
          test-ns (create-ns test-symbol)
          state-values (atom [])]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defvar maybe-count [:optional :u24] nil))
          (eval '(az/defvar last-result
                   [:error-union [:error-set [NoValue]] :u24]
                   7))
          (eval '(az/defvar recent-values
                   [:slice-const :u24]
                   (& [1 2])))
          (eval '(az/defn missing-value
                   {:attrs #{:public :implicit-return}}
                   :- [:error-union [:error-set [NoValue]] :u24]
                   []
                   (az/error-value NoValue)))
          (eval '(az/defn marker :- :u8 [] 1)))
        (let [maybe-count (var-get (ns-resolve test-ns 'maybe-count))
              last-result (var-get (ns-resolve test-ns 'last-result))
              recent-values (var-get (ns-resolve test-ns 'recent-values))
              missing-result ((ns-resolve test-ns 'missing-value))
              missing-value (az/value missing-result)]
          (reset! state-values
                  [maybe-count last-result recent-values missing-result])
          (is (nil? (az/value maybe-count)))
          (is (= {:ok 7} (az/value last-result)))
          (is (= [1 2] (az/value recent-values)))
          (is (= 16777215 (az/set-value! maybe-count 16777215)))
          (is (= {:ok 66051}
                 (az/set-value! last-result {:ok 66051})))
          (is (= [3 16777215]
                 (az/set-value! recent-values [3 16777215])))
          (is (= missing-value (az/set-value! last-result missing-value)))
          ;; Publish another compatible module generation. Existing state
          ;; handles retain both the canonical addresses and their helper ABI.
          (binding [*ns* test-ns]
            (eval '(az/defn marker :- :u8 [] 2)))
          (is (= 2 ((ns-resolve test-ns 'marker))))
          (is (= 16777215 (az/value maybe-count)))
          (is (= :NoValue (get-in (az/value last-result) [:error :name])))
          (is (= [3 16777215] (az/value recent-values))))
        (finally
          (doseq [value @state-values :when (az/zig-value? value)]
            (az/close! value))
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest deeply-nested-native-values-round-trip-test
  (testing "optional, slice, and collection codecs compose without layout guesses"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.nested-native-value-"
                                   fixture-suffix))
          test-ns (create-ns test-symbol)
          native-values (atom [])]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defconst initial-maybe-array
                   [:array 2 [:optional :u24]]
                   [nil 7]))
          (eval '(az/defvar live-maybe-array
                   [:array 2 [:optional :u24]]
                   [nil 9]))
          (eval '(az/defstruct NestedHolder
                   [[:items [:optional [:slice-const [:optional :u24]]]]
                    [:counts [:array 2 [:optional :u24]]]]))
          (eval '(az/defn echo-nested-items
                   {:attrs #{:public :implicit-return}}
                   :- [:optional [:slice-const [:optional :u24]]]
                   [items :- [:optional [:slice-const [:optional :u24]]]]
                   items))
          (eval '(az/defn maybe-nested-result
                   {:attrs #{:public :implicit-return}}
                   :- [:error-union [:error-set [NoValue]]
                       [:optional :u24]]
                   [fail :- :bool]
                   (if fail (az/error-value NoValue) nil)))
          (eval '(az/defn echo-nested-results
                   {:attrs #{:public :implicit-return}}
                   :- [:array 2
                       [:error-union [:error-set [NoValue]]
                        [:optional :u24]]]
                   [results :- [:array 2
                                [:error-union [:error-set [NoValue]]
                                 [:optional :u24]]]]
                   results)))
        (let [initial (var-get (ns-resolve test-ns 'initial-maybe-array))
              state (var-get (ns-resolve test-ns 'live-maybe-array))
              echo (ns-resolve test-ns 'echo-nested-items)
              absent (echo nil)
              present (echo [nil 42 16777215])
              maybe-result (ns-resolve test-ns 'maybe-nested-result)
              failed (maybe-result true)
              error-value (az/value failed)
              result-array
              ((ns-resolve test-ns 'echo-nested-results)
               [error-value {:ok nil}])
              holder-type (var-get (ns-resolve test-ns 'NestedHolder))
              holder (holder-type {:items [1 nil 66051]
                                   :counts [nil 16777215]})]
          (reset! native-values
                  [initial state absent present failed result-array holder])
          (is (= [nil 7] (az/value initial)))
          (is (= [nil 9] (az/value state)))
          (is (= [42 nil] (az/set-value! state [42 nil])))
          (is (nil? (az/value absent)))
          (is (= [nil 42 16777215] (az/value present)))
          (is (= :NoValue (get-in error-value [:error :name])))
          (is (= [error-value {:ok nil}] (az/value result-array)))
          (is (= {:items [1 nil 66051]
                  :counts [nil 16777215]}
                 (az/value holder))))
        (finally
          (doseq [value @native-values :when (az/zig-value? value)]
            (az/close! value))
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest packed-structs-use-clojure-field-maps-test
  (testing "packed fields decode, pprint, construct, and pass without layout guesses"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.packed-value-" fixture-suffix))
          test-ns (create-ns test-symbol)
          constant (atom nil)
          state-value (atom nil)
          constructed (atom nil)
          returned (atom nil)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defstruct Flags
                   {:layout :packed :attrs #{:public}}
                   [[:enabled :bool]
                    [:opcode :u3]
                    [:reserved :u4]]))
          (eval '(az/defconst default-flags
                   Flags
                   (Flags {:enabled true :opcode 5 :reserved 9})))
          (eval '(az/defvar current-flags
                   Flags
                   (Flags {:enabled false :opcode 0 :reserved 0})))
          (eval '(az/defn identity-flags
                   {:attrs #{:public :implicit-return}}
                   :- Flags
                   [flags :- Flags]
                   flags)))
        (reset! constant (var-get (ns-resolve test-ns 'default-flags)))
        (reset! state-value (var-get (ns-resolve test-ns 'current-flags)))
        (let [Flags (var-get (ns-resolve test-ns 'Flags))]
          (is (az/zig-type? Flags))
          (reset! constructed
                  (Flags {:enabled false :opcode 6 :reserved 2})))
        (reset! returned
                ((ns-resolve test-ns 'identity-flags)
                 {:enabled true :opcode 3 :reserved 10}))
        (is (= "{:enabled true, :opcode 5, :reserved 9}"
               (pr-str @constant)))
        (is (= {:enabled false :opcode 6 :reserved 2}
               (az/value @constructed)))
        (is (= {:enabled true :opcode 3 :reserved 10}
               (az/value @returned)))
        (is (= {:enabled true :opcode 2 :reserved 4}
               (az/set-value! @state-value
                              {:enabled true :opcode 2 :reserved 4})))
        (is (= "{:enabled true, :opcode 3, :reserved 10}\n"
               (with-out-str (pprint/pprint @returned))))
        (is (= [(unchecked-byte 0xa7)] (az/native-bytes @returned)))
        (finally
          (doseq [value [@returned @constructed @state-value @constant]
                  :when (az/zig-value? value)]
            (.close ^java.lang.AutoCloseable value))
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest extern-structs-use-zig-reported-layout-test
  (testing "extern structs construct, decode, pprint, and cross the native bridge"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.extern-value-" fixture-suffix))
          test-ns (create-ns test-symbol)
          constant (atom nil)
          constructed (atom nil)
          returned (atom nil)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defstruct Point
                   {:layout :extern :attrs #{:public}}
                   [[:x :i32]
                    [:y :f64]
                    [:enabled :u8]]))
          (eval '(az/defconst origin
                   Point
                   (Point {:x 0 :y 0.0 :enabled 1})))
          (eval '(az/defn identity-point
                   {:attrs #{:public :implicit-return}}
                   :- Point
                   [point :- Point]
                   point)))
        (reset! constant (var-get (ns-resolve test-ns 'origin)))
        (is (= {:x 0 :y 0.0 :enabled 1} (az/value @constant)))
        ;; Direct map coercion requests Zig's layout accessors itself; users do
        ;; not have to invoke the explicit constructor first.
        (reset! returned
                ((ns-resolve test-ns 'identity-point)
                 {:x 12 :y -3.25 :enabled 0}))
        (let [Point (var-get (ns-resolve test-ns 'Point))]
          (is (az/zig-type? Point))
          (reset! constructed
                  (Point {:x -7 :y 2.5 :enabled 1})))
        (is (= {:x -7 :y 2.5 :enabled 1} (az/value @constructed)))
        (is (= {:x 12 :y -3.25 :enabled 0} (az/value @returned)))
        (is (= "{:x 12, :y -3.25, :enabled 0}\n"
               (with-out-str (pprint/pprint @returned))))
        (finally
          (doseq [value [@returned @constructed @constant]
                  :when (az/zig-value? value)]
            (.close ^java.lang.AutoCloseable value))
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest arrays-and-simd-vectors-use-clojure-vectors-test
  (testing "native arrays and SIMD vectors round-trip as Clojure vectors"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.sequence-value-" fixture-suffix))
          test-ns (create-ns test-symbol)
          array-value (atom nil)
          vector-value (atom nil)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defn identity-array
                   {:attrs #{:public :implicit-return}}
                   :- [:array 3 :u24]
                   [values :- [:array 3 :u24]]
                   values))
          (eval '(az/defn identity-vector
                   {:attrs #{:public :implicit-return}}
                   :- [:vector 4 :i16]
                   [values :- [:vector 4 :i16]]
                   values)))
        (reset! array-value
                ((ns-resolve test-ns 'identity-array)
                 [1 16777215 66051]))
        (reset! vector-value
                ((ns-resolve test-ns 'identity-vector)
                 [-32768 -1 0 32767]))
        (is (= [1 16777215 66051] (az/value @array-value)))
        (is (= [-32768 -1 0 32767] (az/value @vector-value)))
        (is (= "[1 16777215 66051]\n"
               (with-out-str (pprint/pprint @array-value))))
        (is (= 12 (count (az/native-bytes @array-value))))
        (finally
          (doseq [value [@array-value @vector-value]
                  :when (az/zig-value? value)]
            (.close ^java.lang.AutoCloseable value))
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest nested-packed-structs-remain-semantic-maps-test
  (testing "nested packed structs use nested field maps and Zig bit ordering"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.nested-packed-" fixture-suffix))
          test-ns (create-ns test-symbol)
          constructed (atom nil)
          returned (atom nil)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defstruct Inner
                   {:layout :packed :attrs #{:public}}
                   [[:low :u3]
                    [:high :bool]]))
          (eval '(az/defstruct Outer
                   {:layout :packed :attrs #{:public}}
                   [[:inner Inner]
                    [:tail :u4]]))
          (eval '(az/defn identity-outer
                   {:attrs #{:public :implicit-return}}
                   :- Outer
                   [value :- Outer]
                   value)))
        (let [Outer (var-get (ns-resolve test-ns 'Outer))]
          (reset! constructed
                  (Outer {:inner {:low 5 :high true} :tail 10})))
        (reset! returned
                ((ns-resolve test-ns 'identity-outer)
                 @constructed))
        (is (= {:inner {:low 5 :high true} :tail 10}
               (az/value @constructed)))
        (is (= {:inner {:low 5 :high true} :tail 10}
               (az/value @returned)))
        (is (= [(unchecked-byte 0xad)] (az/native-bytes @returned)))
        (finally
          (doseq [value [@returned @constructed]
                  :when (az/zig-value? value)]
            (.close ^java.lang.AutoCloseable value))
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest enums-use-clojure-keywords-test
  (testing "container enum Vars are constructors and enum values are keywords"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.enum-value-" fixture-suffix))
          test-ns (create-ns test-symbol)
          constant (atom nil)
          constructed (atom nil)
          returned (atom nil)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defconst Mode
                   {:attrs #{:public}}
                   (az/container
                    {:kind :enum :layout :normal :argument :u8}
                    (az/enum-field-decl idle)
                    (az/enum-field-decl running 7)
                    (az/enum-field-decl stopped))))
          (eval '(az/defconst default-mode Mode :.running))
          (eval '(az/defn identity-mode
                   {:attrs #{:public :implicit-return}}
                   :- Mode
                   [mode :- Mode]
                   mode)))
        (let [Mode (var-get (ns-resolve test-ns 'Mode))]
          (is (az/zig-type? Mode))
          (reset! constructed (Mode :stopped)))
        (reset! constant (var-get (ns-resolve test-ns 'default-mode)))
        (reset! returned
                ((ns-resolve test-ns 'identity-mode) @constructed))
        (is (= :running (az/value @constant)))
        (is (= :stopped (az/value @constructed)))
        (is (= :stopped (az/value @returned)))
        (is (= :idle
               (az/value ((ns-resolve test-ns 'identity-mode) :idle))))
        (finally
          (doseq [value [@returned @constructed @constant]
                  :when (az/zig-value? value)]
            (az/close! value))
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest converted-container-struct-is-a-clojure-constructor-test
  (testing "defconst container structs generated from Zig are callable types"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.container-struct-" fixture-suffix))
          test-ns (create-ns test-symbol)
          constructed (atom nil)
          returned (atom nil)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defconst Point
                   {:attrs #{:public}}
                   (az/container
                    {:kind :struct :layout :extern}
                    (az/field-decl x :i32)
                    (az/field-decl y :f64))))
          (eval '(az/defn identity-point
                   {:attrs #{:public :implicit-return}}
                   :- Point
                   [point :- Point]
                   point)))
        (let [Point (var-get (ns-resolve test-ns 'Point))]
          (is (az/zig-type? Point))
          (reset! constructed (Point {:x -9 :y 1.25})))
        (reset! returned
                ((ns-resolve test-ns 'identity-point) @constructed))
        (is (= {:x -9 :y 1.25} (az/value @constructed)))
        (is (= {:x -9 :y 1.25} (az/value @returned)))
        (finally
          (doseq [value [@returned @constructed]
                  :when (az/zig-value? value)]
            (az/close! value))
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest tagged-unions-use-single-entry-clojure-maps-test
  (testing "Zig decides the active tagged-union field for construction and decoding"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.union-value-" fixture-suffix))
          test-ns (create-ns test-symbol)
          constructed (atom nil)
          returned (atom nil)
          empty-value (atom nil)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defconst Value
                   {:attrs #{:public}}
                   (az/container
                    {:kind :union :layout :normal
                     :enum? true :argument :u8}
                    (az/field-decl integer :i32)
                    (az/field-decl floating :f64)
                    (az/field-decl none :void))))
          (eval '(az/defn identity-value
                   {:attrs #{:public :implicit-return}}
                   :- Value
                   [value :- Value]
                   value)))
        (reset! returned
                ((ns-resolve test-ns 'identity-value) {:floating 2.5}))
        (let [Value (var-get (ns-resolve test-ns 'Value))]
          (is (az/zig-type? Value))
          (reset! constructed (Value {:integer -42}))
          (reset! empty-value (Value {:none nil})))
        (is (= {:floating 2.5} (az/value @returned)))
        (is (= {:integer -42} (az/value @constructed)))
        (is (= {:none nil} (az/value @empty-value)))
        (is (not (str/includes? (az/source test-symbol)
                                "__aguafria_jvm_type_")))
        (finally
          (doseq [value [@empty-value @returned @constructed]
                  :when (az/zig-value? value)]
            (az/close! value))
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest struct-optional-fields-use-nil-or-values-test
  (testing "Zig supplies optional presence/payload access instead of layout guesses"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.optional-field-" fixture-suffix))
          test-ns (create-ns test-symbol)
          constructed (atom nil)
          returned (atom nil)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defstruct MaybeValues
                   {:layout :normal :attrs #{:public}}
                   [[:count [:optional :u32]]
                    [:ratio [:optional :f64]]]))
          (eval '(az/defn identity-maybe-values
                   {:attrs #{:public :implicit-return}}
                   :- MaybeValues
                   [value :- MaybeValues]
                   value)))
        (reset! returned
                ((ns-resolve test-ns 'identity-maybe-values)
                 {:count nil :ratio 2.5}))
        (let [MaybeValues (var-get (ns-resolve test-ns 'MaybeValues))]
          (reset! constructed
                  (MaybeValues {:count 4294967295 :ratio nil})))
        (is (= {:count nil :ratio 2.5} (az/value @returned)))
        (is (= {:count 4294967295 :ratio nil}
               (az/value @constructed)))
        (finally
          (doseq [value [@returned @constructed]
                  :when (az/zig-value? value)]
            (az/close! value))
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest optional-function-values-use-nil-or-payload-test
  (testing "top-level optional arguments/results use Zig semantic helpers"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.optional-value-" fixture-suffix))
          test-ns (create-ns test-symbol)
          constant (atom nil)
          absent (atom nil)
          present (atom nil)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defconst default-optional [:optional :u32] 42))
          (eval '(az/defn identity-optional
                   {:attrs #{:public :implicit-return}}
                   :- [:optional :u32]
                   [value :- [:optional :u32]]
                   value)))
        (reset! constant (var-get (ns-resolve test-ns 'default-optional)))
        (reset! absent ((ns-resolve test-ns 'identity-optional) nil))
        (reset! present
                ((ns-resolve test-ns 'identity-optional) 4294967295))
        (is (nil? (az/value @absent)))
        (is (= 42 (az/value @constant)))
        (is (= 4294967295 (az/value @present)))
        (is (= "nil" (pr-str @absent)))
        (finally
          (doseq [value [@present @absent @constant]
                  :when (az/zig-value? value)]
            (az/close! value))
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest borrowed-pointers-are-typed-clojure-values-test
  (testing "pointer values retain their Zig type and point at live native state"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.pointer-value-" fixture-suffix))
          test-ns (create-ns test-symbol)
          state-value (atom nil)
          pointer-value (atom nil)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defvar pointed :i32 42))
          (eval '(az/defn pointed-address
                   {:attrs #{:public :implicit-return}}
                   :- [:* :i32]
                   []
                   (& pointed)))
          (eval '(az/defn read-pointed
                   {:attrs #{:public :implicit-return}}
                   :- :i32
                   [pointer :- [:* :i32]]
                   (az/deref pointer))))
        (reset! state-value (var-get (ns-resolve test-ns 'pointed)))
        (reset! pointer-value ((ns-resolve test-ns 'pointed-address)))
        (let [pointer (az/value @pointer-value)]
          (is (az/zig-pointer? pointer))
          (is (pos? (az/pointer-address pointer)))
          (is (= [:* :i32] (az/pointer-type pointer)))
          (is (= 4 (.byteSize (az/pointer-segment pointer 4))))
          (is (= 42 ((ns-resolve test-ns 'read-pointed) pointer)))
          (az/set-value! @state-value 99)
          (is (= 99 ((ns-resolve test-ns 'read-pointed) pointer))))
        (finally
          (doseq [value [@pointer-value @state-value]
                  :when (az/zig-value? value)]
            (az/close! value))
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest slices-use-clojure-vectors-with-owned-call-storage-test
  (testing "slice vectors round-trip, including odd-width elements and empty slices"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.slice-value-" fixture-suffix))
          test-ns (create-ns test-symbol)
          returned-values (atom [])
          holder-value (atom nil)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defn echo-slice
                   {:attrs #{:public :implicit-return}}
                   :- [:slice-const :u24]
                   [items :- [:slice-const :u24]]
                   items))
          (eval '(az/defstruct SliceHolder
                   [[:items [:slice-const :u24]]])))
        (let [echo (ns-resolve test-ns 'echo-slice)
              full (echo [0 16777215 42])
              empty (echo [])
              holder-type (var-get (ns-resolve test-ns 'SliceHolder))
              holder (holder-type {:items [1 66051]})]
          (reset! returned-values [full empty])
          (reset! holder-value holder)
          (is (= [0 16777215 42] (az/value full)))
          (is (= [] (az/value empty)))
          (is (= {:items [1 66051]} (az/value holder))))
        (finally
          (doseq [value (concat @returned-values [@holder-value])
                  :when (az/zig-value? value)]
            (az/close! value))
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest error-unions-use-explicit-ok-or-error-maps-test
  (testing "error-union payloads and named errors remain exact and reusable"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.error-value-" fixture-suffix))
          test-ns (create-ns test-symbol)
          returned-values (atom [])]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defn maybe-value
                   {:attrs #{:public :implicit-return}}
                   :- [:error-union [:error-set [NoValue]] :u24]
                   [fail :- :bool]
                   (if fail (az/error-value NoValue) 66051)))
          (eval '(az/defn echo-result
                   {:attrs #{:public :implicit-return}}
                   :- [:error-union [:error-set [NoValue]] :u24]
                   [result :- [:error-union [:error-set [NoValue]] :u24]]
                   result))
          (eval '(az/defstruct ResultHolder
                   [[:result [:error-union [:error-set [NoValue]] :u24]]])))
        (let [maybe-value (ns-resolve test-ns 'maybe-value)
              echo-result (ns-resolve test-ns 'echo-result)
              ok (maybe-value false)
              error (maybe-value true)
              error-value (az/value error)
              echoed-ok (echo-result {:ok 16777215})
              echoed-error (echo-result error-value)
              holder-type (var-get (ns-resolve test-ns 'ResultHolder))
              holder-ok (holder-type {:result {:ok 7}})
              holder-error (holder-type {:result error-value})]
          (reset! returned-values
                  [ok error echoed-ok echoed-error holder-ok holder-error])
          (is (= {:ok 66051} (az/value ok)))
          (is (= :NoValue (get-in error-value [:error :name])))
          (is (pos? (get-in error-value [:error :code])))
          (is (= {:ok 16777215} (az/value echoed-ok)))
          (is (= error-value (az/value echoed-error)))
          (is (= {:result {:ok 7}} (az/value holder-ok)))
          (is (= {:result error-value} (az/value holder-error))))
        (finally
          (doseq [value @returned-values
                  :when (az/zig-value? value)]
            (az/close! value))
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest native-values-pin-hot-reload-generations-test
  (testing "a native value survives compatible reload and releases its old dylib"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.value-reload-" fixture-suffix))
          test-ns (create-ns test-symbol)
          old-value (atom nil)
          returned (atom nil)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defstruct Payload
                   {:layout :packed :attrs #{:public}}
                   [[:value :u8]]))
          (eval '(az/defn identity-payload
                   {:attrs #{:public :implicit-return}}
                   :- Payload
                   [payload :- Payload]
                   payload)))
        (let [Payload (var-get (ns-resolve test-ns 'Payload))]
          (reset! old-value (Payload {:value 41})))
        (is (= {:value 41} (az/value @old-value)))
        (let [old-generation (:generation (az/value-info @old-value))]
          (binding [*ns* test-ns]
            (eval '(az/defn identity-payload
                     {:attrs #{:public :implicit-return}}
                     :- Payload
                     [payload :- Payload]
                     (if true payload payload))))
          (reset! returned
                  ((ns-resolve test-ns 'identity-payload) @old-value))
          (is (= {:value 41} (az/value @returned)))
          (is (pos? (or (some #(when (= old-generation (:generation %))
                                (:native-value-reference-count %))
                             (:native-generations (az/module-info test-symbol)))
                        0)))
          (az/close! @old-value)
          (is (not (pos? (or (some #(when (= old-generation (:generation %))
                                      (:native-value-reference-count %))
                                   (:native-generations
                                    (az/module-info test-symbol)))
                              0)))))
        (finally
          (doseq [value [@returned @old-value]
                  :when (az/zig-value? value)]
            (az/close! value))
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest native-values-retain-breaking-type-generations-test
  (testing "a value keeps its exact old schema after a breaking type publication"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.breaking-value-" fixture-suffix))
          test-ns (create-ns test-symbol)
          old-value (atom nil)
          new-value (atom nil)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defstruct Payload
                   [[:items [:slice-const :u8]]])))
        (reset! old-value
                ((var-get (ns-resolve test-ns 'Payload)) {:items [1 2]}))
        (is (= {:items [1 2]} (az/value @old-value)))
        (let [old-generation (:generation (az/value-info @old-value))]
          (binding [*ns* test-ns]
            (eval '(az/defstruct Payload
                     [[:items [:slice-const :u16]]])))
          (reset! new-value
                  ((var-get (ns-resolve test-ns 'Payload)) {:items [65535]}))
          (is (= {:items [65535]} (az/value @new-value)))
          (is (some? old-generation))
          (is (pos? (or (some #(when (= old-generation (:generation %))
                                (:native-value-reference-count %))
                             (:native-generations (az/module-info test-symbol)))
                        0))
              (pr-str {:old-generation old-generation
                       :generations
                       (:native-generations (az/module-info test-symbol))}))
          (az/close! @old-value)
          (is (not (pos? (or (some #(when (= old-generation (:generation %))
                                      (:native-value-reference-count %))
                                   (:native-generations
                                    (az/module-info test-symbol)))
                              0)))))
        (finally
          (doseq [value [@new-value @old-value]
                  :when (az/zig-value? value)]
            (az/close! value))
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest unreachable-native-values-release-generations-test
  (testing "Cleaner retirement releases a generation when explicit close is omitted"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.cleaner-value-" fixture-suffix))
          test-ns (create-ns test-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defstruct Payload
                   {:layout :packed}
                   [[:value :u8]])))
        (let [[weak-value generation]
              ((fn []
                 (let [value
                       ((var-get (ns-resolve test-ns 'Payload)) {:value 42})]
                   ;; Force materialization and Cleaner registration before
                   ;; this lexical scope drops its only strong reference.
                   (az/value value)
                   [(java.lang.ref.WeakReference. value)
                    (:generation (az/value-info value))])))
              reference-count
              (fn []
                (or (some #(when (= generation (:generation %))
                             (:native-value-reference-count %))
                          (:native-generations (az/module-info test-symbol)))
                    0))]
          (is (pos? (reference-count)))
          (loop [attempt 0]
            (when (and (< attempt 200)
                       (or (.get weak-value) (pos? (reference-count))))
              (System/gc)
              (Thread/sleep 25)
              (recur (inc attempt))))
          (is (nil? (.get weak-value)))
          (is (zero? (reference-count))))
        (finally
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest development-root-public-declarations-reach-dependencies-test
  (testing "@import(\"root\") observes the converted application root"
    (let [old-config (az/configuration)
          suffix fixture-suffix
          dependency-symbol
          (symbol (str "aguafria.root-context-dependency-" suffix))
          application-symbol
          (symbol (str "aguafria.root-context-application-" suffix))
          dependency-ns (create-ns dependency-symbol)
          application-ns (create-ns application-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (doseq [target [dependency-ns application-ns]]
          (binding [*ns* target]
            (refer 'clojure.core)
            (alias 'az 'aguafria.zig)
            (alias 'ak 'aguafria.keyword)))
        (binding [*ns* dependency-ns]
          (eval
           '(az/defconst root
              {:zig/import-name "root"}
              (ak/import "root")))
          (eval
           '(az/defconst selected_config :u32
              (if (ak/hasDecl root "custom_config")
                (az/field root custom_config)
                0))))
        (binding [*ns* application-ns]
          (alias 'dependency dependency-symbol)
          (eval '(az/defconst custom_config {:attrs #{:public}} :u32 7))
          (eval
           '(az/defn read_context :- :u32 []
              dependency/selected_config)))
        (is (= 7 ((ns-resolve application-ns 'read_context))))
        (finally
          (az/configure! old-config)
          (remove-ns application-symbol)
          (remove-ns dependency-symbol))))))

(deftest running-native-host-follows-compatible-var-swap-test
  (testing "an already-running main follows a compatible callee publication"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.running-host-" fixture-suffix))
          test-ns (create-ns test-symbol)
          handle (atom nil)
          await-value
          (fn [expected]
            (loop [attempt 0]
              (let [value ((ns-resolve test-ns 'observed-value))]
                (cond
                  (= expected value) value
                  (< attempt 500) (do (Thread/sleep 10) (recur (inc attempt)))
                  :else value))))]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (alias 'std-process 'aguafria.std.process)
          (eval '(az/defvar running :bool true))
          (eval '(az/defvar observed :i32 0))
          (eval '(az/defn logic
                   {:attrs #{:public :implicit-return}}
                   :- :i32 [] 1))
          (eval '(az/defn observed-value :- :i32 [] observed))
          (eval '(az/defn stop :- :void [] (set! running false)))
          (eval '(az/defn main
                   {:zig/qualifiers "!" :attrs #{:public}}
                   :- :void
                   [[process-init std-process/Init]]
                   (set! _ process-init)
                   (while running
                     (set! observed (logic))))))
        (reset! handle
                (host/start! (ns-resolve test-ns 'main) [] {:argv0 "fixture"}))
        (is (= 1 (await-value 1)))
        (binding [*ns* test-ns]
          (eval '(az/defn logic
                   {:attrs #{:public :implicit-return}}
                   :- :i32 [] 2)))
        (is (= 2 (await-value 2)))
        ((ns-resolve test-ns 'stop))
        (is (= 0 (:exit-code (host/await! @handle))))
        (is (= 2 ((ns-resolve test-ns 'observed-value))))
        (finally
          (when (and @handle (:active? (host/info @handle)))
            (try
              ((ns-resolve test-ns 'stop))
              (host/await! @handle)
              (catch Throwable _)))
          (az/configure! old-config))))))

(deftest running-native-host-pins-breaking-type-generation-test
  (testing "a host keeps its complete old dispatch graph across a layout break"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.running-type-host-"
                                   fixture-suffix))
          test-ns (create-ns test-symbol)
          handle (atom nil)
          pid (.pid (java.lang.ProcessHandle/current))
          await-observed
          (fn [expected]
            (loop [attempt 0]
              (let [value ((ns-resolve test-ns 'observed-value))]
                (cond
                  (= expected value) value
                  (< attempt 500) (do (Thread/sleep 10) (recur (inc attempt)))
                  :else value))))]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (alias 'ak 'aguafria.keyword)
          (alias 'std-process 'aguafria.std.process)
          (eval '(az/defvar running :bool true))
          (eval '(az/defvar observed :usize 0))
          (eval '(az/defstruct Item [[:value :i32]]))
          (eval '(az/defn item-size :- :usize [] (ak/sizeOf Item)))
          (eval '(az/defn observed-value :- :usize [] observed))
          (eval '(az/defn stop :- :void [] (set! running false)))
          (eval '(az/defn reset-running :- :void [] (set! running true)))
          (eval '(az/defn main
                   {:zig/qualifiers "!" :attrs #{:public}}
                   :- :void
                   [[process-init std-process/Init]]
                   (set! _ process-init)
                   (while running
                     (set! observed (item-size))))))
        (reset! handle
                (host/start! (ns-resolve test-ns 'main) [] {:argv0 "fixture"}))
        (is (= 4 (await-observed 4)))

        (binding [*ns* test-ns]
          (eval '(az/defstruct Item [[:value :i32] [:extra :i32]])))
        (is (= 4 ((ns-resolve test-ns 'item-size))))
        (binding [*ns* test-ns]
          (eval '(az/defn item-size :- :usize [] (ak/sizeOf Item))))
        (is (= 8 ((ns-resolve test-ns 'item-size))))
        (Thread/sleep 100)
        (is (= 4 ((ns-resolve test-ns 'observed-value))))
        (is (:dispatch-frozen? (host/info @handle)))
        (is (= [:retained :breaking]
               (mapv :status
                     (az/type-versions (ns-resolve test-ns 'Item)))))

        ((ns-resolve test-ns 'stop))
        (let [old-handle @handle]
          (is (= 0 (:exit-code (host/await! old-handle))))
          ;; A breaking type never silently redirects an untouched caller,
          ;; including the host root. Explicitly reevaluate `main` at the safe
          ;; boundary so the replacement adopts `item-size@v2`.
          (binding [*ns* test-ns]
            (eval '(az/defn main
                     {:zig/qualifiers "!" :attrs #{:public}}
                     :- :void
                     [[process-init std-process/Init]]
                     (set! _ process-init)
                     (while running
                       (set! observed (item-size))))))
          ((ns-resolve test-ns 'reset-running))
          (reset! handle (host/restart! old-handle))
          (is (= (:id old-handle) (:replaces-host-id @handle)))
          (is (= pid (.pid (java.lang.ProcessHandle/current))))
          (is (= 8 (await-observed 8)))
          ((ns-resolve test-ns 'stop))
          (is (= 0 (:exit-code (host/await! @handle)))))
        (finally
          (when (and @handle (:active? (host/info @handle)))
            (try
              ((ns-resolve test-ns 'stop))
              (host/await! @handle)
              (catch Throwable _)))
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest compile-and-invoke-test
  (testing "latest async module generation is callable"
    (is (= 12 (composed 3)))
    (is (= 9 (constructed-point-sum)))
    (is (= 45 (sum-to 10)))
    (is (= 20 (external-quadruple 5)))
    (is (= 10 (simd-sum4 1 2 3 4)))
    (is (= 42 (keyword-int-cast 42)))
    (is (= 6 (reader-safe-bit-xor 5 3)))
    (is (= 42 (comptime-answer-value)))
    (is (= 9 (abs-i32 -9)))
    (is (= 9 (abs-i32 9))))

  (testing "declarations are ordinary standalone Zig"
    (let [source (az/source 'aguafria.zig-integration-test)
          reload-source (:reload-source
                         (az/module-info 'aguafria.zig-integration-test))]
      (is (str/includes? source "const std = @import(\"std\");"))
      (is (str/includes? source "const extra_math = @import(\"extra_math\");"))
      (is (str/includes? source "pub const Point = struct"))
      (is (str/includes? source "pub const multiplier: i32 = 3;"))
      (is (str/includes? source "pub fn sum_point(point: Point) i32"))
      (is (str/includes? source "Point{.x = 4, .y = 5}"))
      (is (not (str/includes? source "@import(\"aguafria")))
      (is (not (str/includes? source "__aguafria_")))
      (is (str/includes? reload-source "_set_dispatch"))))

  (testing "external Zig members are real, inspectable Clojure Vars"
    (let [test-ns (the-ns 'aguafria.zig-integration-test)
          member (ns-resolve test-ns 'extra-math/quadruple)]
      (is (var? (ns-resolve test-ns 'std)))
      (is (var? member))
      (is (= "extra_math.quadruple"
             (get-in (meta member) [:aguafria/zig-reference :zig-name])))))

  (testing "newest generation has been published"
    (let [{:keys [generation requested-generation pending? error source-path
                  library-path]} (az/module-info 'aguafria.zig-integration-test)]
      (is (= generation requested-generation))
      (is (false? pending?))
      (is (nil? error))
      (is (.isFile (java.io.File. source-path)))
      (is (.isFile (java.io.File. library-path))))))

(deftest repl-metadata-test
  (is (= "Increment an integer in Zig." (:doc (meta #'base))))
  (is (= '([x :- :i32]) (:arglists (meta #'base))))
  (is (= :fn (get-in (meta #'base) [:aguafria/declaration :kind])))
  (is (= ["aguafria.zig-integration-test" :fn "base"]
         (get-in (meta #'base) [:aguafria/declaration :logical-id])))
  (is (= 64 (count (get-in (meta #'base)
                           [:aguafria/declaration :abi-fingerprint]))))
  (is (= 64 (count (get-in (meta #'Point)
                           [:aguafria/declaration :schema-fingerprint]))))
  (is (= '[(aguafria.keyword/intCast value)]
         (get-in (meta #'keyword-int-cast) [:aguafria/declaration :body])))
  (is (= '(aguafria.keyword/Vector 4 :i32)
         (get-in (meta #'simd-lane-sum)
                 [:aguafria/declaration :args 0 :type])))
  (is (= {:doc "Horizontal coordinate"}
         (get-in (meta #'Point)
                 [:aguafria/declaration :fields 0 :properties]))))

(deftest explicit-recompile-test
  (az/await! 'aguafria.zig-integration-test)
  (let [before (:published-generation
                (az/stats 'aguafria.zig-integration-test))]
    (az/recompile! 'aguafria.zig-integration-test)
    (az/await! 'aguafria.zig-integration-test)
    (is (= (inc before)
           (:published-generation
            (az/stats 'aguafria.zig-integration-test))))
    (is (= 10 (simd-sum4 1 2 3 4)))))

(deftest content-addressed-development-modules-use-native-cache-test
  (testing "unchanged Aguafria-owned dependency files do not disable cache hits"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.cache-fixture-" fixture-suffix))
          test-ns (create-ns test-symbol)]
      (try
        (az/configure! {:async? false :modules {} :zig-args []})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defn cached_value :- :u32 [] 42)))
        (is (= 42 ((ns-resolve test-ns 'cached_value))))
        (let [initial-hash (get-in (az/stats test-symbol) [:last-build :hash])]
          (az/recompile! test-symbol)
          (let [build (:last-build (az/stats test-symbol))]
            (is (true? (:cached? build)))
            (is (= initial-hash (:hash build)))))
        (finally
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest unchanged-and-incremental-registration-test
  (testing "identical declarations skip Zig and body edits publish a live slice"
    (let [old-config (az/configuration)
          module (str "aguafria.incremental-fixture-" fixture-suffix)
          qualified-name (symbol module "calculate")
          declaration
          (fn [doc amount]
            (runtime/declaration-info
             {:kind :fn
              :name 'calculate
              :qualified-name qualified-name
              :declaration-key [:fn 'calculate]
              :module module
              :args [{:name 'x :type :i32 :properties {}}]
              :return :i32
              :body [(list '+ 'x amount)]
              :doc doc
              :export? true
              :public? true
              :implicit-return? true
              :source {:file "test/aguafria/zig_integration_test.clj"
                       :line (:line (meta #'unchanged-and-incremental-registration-test))
                       :column 1}}))]
      (try
        (az/configure! {:async? false :modules {}})
        (runtime/register-declaration! (declaration "v1" 1))
        (is (= 8 (runtime/invoke! qualified-name [7])))
        (let [before (:published-generation (az/stats module))
              unchanged
              (runtime/register-declaration! (declaration "updated docs" 1))]
          (is (:unchanged? unchanged))
          (is (= before (:published-generation (az/stats module)))))

        (runtime/register-declaration! (declaration "v2" 5))
        (is (= 12 (runtime/invoke! qualified-name [7])))
        (is (:partial-publication? (az/stats module)))
        (finally
          (az/configure! old-config))))))

(deftest partial-publication-retains-complete-dependency-source-test
  (testing "a downstream edit sees every declaration after an unrelated live slice"
    (let [old-config (az/configuration)
          provider-symbol (symbol (str "aguafria.partial-provider-" fixture-suffix))
          caller-symbol (symbol (str "aguafria.partial-caller-" fixture-suffix))
          provider-ns (create-ns provider-symbol)
          caller-ns (create-ns caller-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (doseq [target [provider-ns caller-ns]]
          (binding [*ns* target]
            (refer 'clojure.core)
            (alias 'az 'aguafria.zig)))
        (binding [*ns* provider-ns]
          (eval '(az/defstruct Point [[:x :u32]]))
          (eval '(az/defn ^{:export false :public true} read-point
                   :- :u32 [point :- Point]
                   (az/field point x)))
          ;; This newest slice deliberately has no reference to Point or
          ;; read-point. Dependency materialization must still use the complete
          ;; provider source rather than this implementation slice.
          (eval '(az/defn ^{:export false :public true} unrelated
                   :- :u32 [] 7)))
        (is (:partial-publication? (az/stats provider-symbol)))
        (binding [*ns* caller-ns]
          (alias 'provider provider-symbol)
          (eval '(az/defn ^{:export false :public true} use-point
                   :- :u32 [point :- provider/Point]
                   (provider/read-point point))))
        (is (= :finished (:status (az/stats caller-symbol))))
        (finally
          (az/configure! old-config)
          (remove-ns caller-symbol)
          (remove-ns provider-symbol))))))

(deftest declaration-kind-change-replaces-same-zig-name-test
  (testing "a new type kind replaces its name while retaining old native generations"
    (let [old-config (az/configuration)
          module-symbol (symbol (str "aguafria.kind-change-" fixture-suffix))
          module-ns (create-ns module-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* module-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defstruct Value [[:x :u32]]))
          (eval '(az/defconst Value :u32)))
        (let [source (az/source module-symbol)]
          (is (= :finished (:status (az/stats module-symbol))))
          (is (= 1 (count (re-seq #"pub const Value" source))))
          (is (not (str/includes? source "Value = struct"))))
        (finally
          (az/configure! old-config)
          (remove-ns module-symbol))))))

(deftest hot-reload-test
  (testing "a compatible callee edit repoints an already-compiled Zig caller"
    (try
      (is (= 12 (composed 3)))
      (let [base-before (declaration-stat "base")
            composed-before (declaration-stat "composed")]
        (binding [*ns* (the-ns 'aguafria.zig-integration-test)]
          (eval
           '(az/defn base :- :i32
              [x :- :i32]
              (+ x 5))))
        (is (= 24 (composed 3)))
        (let [base-after (declaration-stat "base")
              composed-after (declaration-stat "composed")
              stats-after (az/stats 'aguafria.zig-integration-test)
              retained-generations
              (into #{} (map :generation) (:native-generations stats-after))]
          (is (< (:implementation-generation base-before)
                 (:implementation-generation base-after)))
          (is (= (:implementation-generation composed-before)
                 (:implementation-generation composed-after)))
          (is (contains? retained-generations
                         (:implementation-generation base-after)))
          (is (contains? retained-generations
                         (:implementation-generation composed-after)))
          (is (pos? (:dispatch-version-count stats-after)))))
      (finally
        (binding [*ns* (the-ns 'aguafria.zig-integration-test)]
          (eval
           '(az/defn base
              "Increment an integer in Zig."
              :- :i32
              [x :- :i32]
              (+ x 1))))
        (az/await! 'aguafria.zig-integration-test)))))

(deftest zig-only-compatible-callee-hot-reload-test
  (testing "a non-C-exported Zig helper repoints its already-compiled Zig caller"
    (try
      (is (= 14 (zig-only-composed 3)))
      (let [callee-before (declaration-stat "zig-only-base")
            caller-before (declaration-stat "zig-only-composed")]
        (binding [*ns* (the-ns 'aguafria.zig-integration-test)]
          (eval
           '(az/defn ^{:export false :public true} zig-only-base :- :i32
              [x :- :i32]
              (+ x 10))))
        (is (= 26 (zig-only-composed 3)))
        (let [callee-after (declaration-stat "zig-only-base")
              caller-after (declaration-stat "zig-only-composed")]
          (is (< (:implementation-generation callee-before)
                 (:implementation-generation callee-after)))
          (is (= (:implementation-generation caller-before)
                 (:implementation-generation caller-after)))))
      (finally
        (binding [*ns* (the-ns 'aguafria.zig-integration-test)]
          (eval
           '(az/defn ^{:export false :public true} zig-only-base :- :i32
              [x :- :i32]
              (+ x 4))))
        (az/await! 'aguafria.zig-integration-test)))))

(deftest add-function-and-rewire-existing-caller-test
  (let [pid (.pid (java.lang.ProcessHandle/current))]
    (binding [*ns* (the-ns 'aguafria.zig-integration-test)]
      (eval
       '(az/defn live-existing-b :- :i32
          [x :- :i32]
          (+ x 2))))
    (is (= 5 ((ns-resolve (the-ns 'aguafria.zig-integration-test)
                          'live-existing-b)
              3)))
    (binding [*ns* (the-ns 'aguafria.zig-integration-test)]
      (eval
       '(az/defn live-new-a :- :i32
          [x :- :i32]
          (* x 10)))
      (eval
       '(az/defn live-existing-b :- :i32
          [x :- :i32]
          (+ (live-new-a x) 2))))
    (is (= 32 ((ns-resolve (the-ns 'aguafria.zig-integration-test)
                           'live-existing-b)
               3)))
    (is (= pid (.pid (java.lang.ProcessHandle/current))))))

(deftest add-cross-namespace-function-and-hot-rewire-caller-test
  (testing "a new A Var can be used by new and existing B Vars without restart"
    (let [old-config (az/configuration)
          suffix fixture-suffix
          a-symbol (symbol (str "aguafria.live-a-" suffix))
          b-symbol (symbol (str "aguafria.live-b-" suffix))
          a-ns (create-ns a-symbol)
          b-ns (create-ns b-symbol)
          pid (.pid (java.lang.ProcessHandle/current))]
      (try
        (az/configure! {:async? true :modules {}})
        (doseq [target [a-ns b-ns]]
          (binding [*ns* target]
            (refer 'clojure.core)
            (alias 'az 'aguafria.zig)))
        (binding [*ns* b-ns]
          (alias 'a a-symbol)
          (eval
           '(az/defn existing-b :- :i32
              [x :- :i32]
              (+ x 2))))
        (is (= 5 ((ns-resolve b-ns 'existing-b) 3)))

        (binding [*ns* a-ns]
          (eval
           '(az/defn new-a :- :i32
              [x :- :i32]
              (* x 10)))
          (eval
           '(az/defn ^{:export false :public true} zig-only-a :- :i32
              [x :- :i32]
              (* x 3))))
        (is (= 30 ((ns-resolve a-ns 'new-a) 3)))

        (binding [*ns* b-ns]
          (eval
           '(az/defn existing-b :- :i32
              [x :- :i32]
              (+ (a/new-a x) 2)))
          (eval
           '(az/defn new-b :- :i32
              [x :- :i32]
              (+ (a/new-a x) 5)))
          (eval
           '(az/defn zig-only-b :- :i32
              [x :- :i32]
              (a/zig-only-a x))))
        (is (= 32 ((ns-resolve b-ns 'existing-b) 3)))
        (is (= 35 ((ns-resolve b-ns 'new-b) 3)))
        (is (= 9 ((ns-resolve b-ns 'zig-only-b) 3)))
        (let [b-generation-before
              (->> (:declarations (az/stats b-symbol))
                   (some #(when (= "existing-b" (:name %))
                            (:implementation-generation %))))
              a-v1-abi (-> (ns-resolve a-ns 'new-a)
                           meta :aguafria/declaration :abi-fingerprint)]
          (binding [*ns* a-ns]
            (eval
             '(az/defn new-a :- :i32
                [x :- :i32]
                (* x 20)))
            (eval
             '(az/defn ^{:export false :public true} zig-only-a :- :i32
                [x :- :i32]
                (* x 4))))
          (az/await! a-symbol)
          (is (= 62 ((ns-resolve b-ns 'existing-b) 3)))
          (is (= 65 ((ns-resolve b-ns 'new-b) 3)))
          (is (= 12 ((ns-resolve b-ns 'zig-only-b) 3)))
          (let [b-generation-after
                (->> (:declarations (az/stats b-symbol))
                     (some #(when (= "existing-b" (:name %))
                              (:implementation-generation %))))]
            (is (= b-generation-before b-generation-after)))

          (binding [*ns* a-ns]
            (eval
             '(az/defn new-a :- :i32
                [x :- :i32 y :- :i32]
                (+ (* x 20) y))))
          (az/await! a-symbol)
          (is (= 67 ((ns-resolve a-ns 'new-a) 3 7)))
          (is (= 62 ((ns-resolve b-ns 'existing-b) 3)))
          (is (= 65 ((ns-resolve b-ns 'new-b) 3)))
          (is (= 60 (az/invoke-version! (symbol (str a-symbol) "new-a")
                                        a-v1-abi [3])))
          (is (= 2 (count (az/function-versions
                           (ns-resolve a-ns 'new-a)))))

          ;; Both stale callers are reevaluated before the async quiet point;
          ;; their newest complete B snapshot binds to A@v2 atomically.
          (binding [*ns* b-ns]
            (eval
             '(az/defn existing-b :- :i32
                [x :- :i32]
                (+ (a/new-a x 7) 2)))
            (eval
             '(az/defn new-b :- :i32
                [x :- :i32]
                (+ (a/new-a x 7) 5))))
          (az/await! b-symbol)
          (is (= 69 ((ns-resolve b-ns 'existing-b) 3)))
          (is (= 72 ((ns-resolve b-ns 'new-b) 3)))
          (let [artifact (az/build! b-symbol
                                    {:kind :dynamic-lib
                                     :name (str "cross-namespace-" suffix)
                                     :optimize "ReleaseFast"})
                dependency-prefix (str "-M" a-symbol "=")
                dependency-argument
                (some #(when (str/starts-with? % dependency-prefix) %)
                      (:command artifact))
                dependency-source
                (subs dependency-argument (count dependency-prefix))]
            (is (.isFile (java.io.File. (:output-path artifact))))
            (is (not (str/includes? (slurp (:source-path artifact))
                                    "__aguafria_")))
            (is (not (str/includes? (slurp dependency-source)
                                    "__aguafria_")))))
        (is (= pid (.pid (java.lang.ProcessHandle/current))))
        (is (str/includes? (az/source b-symbol)
                           (str "@import(\"" a-symbol "\")")))
        (let [all-stats (az/stats)
              a-stats (get-in all-stats [:modules (str a-symbol)])
              b-stats (get-in all-stats [:modules (str b-symbol)])]
          (is (= [(str a-symbol)] (:dependencies b-stats)))
          (is (some #{(str b-symbol)} (:dependents a-stats)))
          (is (= [(str b-symbol)] (:dependency-component-modules b-stats)))
          (is (false? (:cyclic-dependency-component? b-stats))))
        (finally
          (az/configure! old-config)
          (remove-ns b-symbol)
          (remove-ns a-symbol))))))

(deftest inline-function-hot-dispatch-test
  (testing "a forced-inline Zig wrapper keeps existing callers on a live cell"
    (let [old-config (az/configuration)
          module-symbol (symbol (str "aguafria.inline-live-" fixture-suffix))
          module-ns (create-ns module-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* module-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval
           '(az/defn inline-base
              {:zig/prefix "pub inline"
               :attrs #{:public :implicit-return}}
              :- :i32
              [x :- :i32]
              (+ x 1)))
          (eval
           '(az/defn inline-caller :- :i32
              [x :- :i32]
              (inline-base x))))
        (is (= 6 ((ns-resolve module-ns 'inline-caller) 5)))
        (let [caller-generation-before
              (->> (:declarations (az/stats module-symbol))
                   (some #(when (= "inline-caller" (:name %))
                            (:implementation-generation %))))]
          (binding [*ns* module-ns]
            (eval
             '(az/defn inline-base
                {:zig/prefix "pub inline"
                 :attrs #{:public :implicit-return}}
                :- :i32
                [x :- :i32]
                (+ x 2))))
          (is (= 7 ((ns-resolve module-ns 'inline-caller) 5)))
          (is (= caller-generation-before
                 (->> (:declarations (az/stats module-symbol))
                      (some #(when (= "inline-caller" (:name %))
                               (:implementation-generation %))))))
          (is (= 1 (count (az/function-versions
                           (ns-resolve module-ns 'inline-base)))))
          (is (str/includes? (az/source module-symbol)
                             "pub inline fn inline_base")))
        (finally
          (az/configure! old-config)
          (remove-ns module-symbol))))))

(deftest generic-function-edit-republishes-concrete-cross-namespace-callers-test
  (testing "a generic Var edit recompiles monomorphized callers instead of inventing a pointer ABI"
    (let [old-config (az/configuration)
          suffix fixture-suffix
          generic-symbol (symbol (str "aguafria.generic-live-" suffix))
          caller-symbol (symbol (str "aguafria.generic-caller-" suffix))
          generic-ns (create-ns generic-symbol)
          caller-ns (create-ns caller-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (doseq [target [generic-ns caller-ns]]
          (binding [*ns* target]
            (refer 'clojure.core)
            (alias 'az 'aguafria.zig)))
        (binding [*ns* generic-ns]
          (eval
           '(az/defn generic-add
              {:attrs #{:public :implicit-return}}
              :- T
              [[T {:zig/prefix "comptime"} :type]
               [x T]]
              (+ x 1))))
        (binding [*ns* caller-ns]
          (alias 'generic generic-symbol)
          (eval
           '(az/defn concrete-caller :- :i32
              [x :- :i32]
              (generic/generic-add :i32 x))))
        (is (= 6 ((ns-resolve caller-ns 'concrete-caller) 5)))
        (let [caller-generation-before
              (->> (:declarations (az/stats caller-symbol))
                   (some #(when (= "concrete-caller" (:name %))
                            (:implementation-generation %))))]
          (binding [*ns* generic-ns]
            (eval
             '(az/defn generic-add
                {:attrs #{:public :implicit-return}}
                :- T
                [[T {:zig/prefix "comptime"} :type]
                 [x T]]
                (+ x 2))))
          (is (= 7 ((ns-resolve caller-ns 'concrete-caller) 5)))
          (is (< caller-generation-before
                 (->> (:declarations (az/stats caller-symbol))
                      (some #(when (= "concrete-caller" (:name %))
                               (:implementation-generation %))))))
          (is (empty? (az/function-versions
                       (ns-resolve generic-ns 'generic-add)))))
        (finally
          (az/configure! old-config)
          (remove-ns caller-symbol)
          (remove-ns generic-symbol))))))

(deftest composite-zig-signature-hot-dispatch-test
  (testing "Zig callers hot-swap native functions whose ABI is not JVM-scalar"
    (let [old-config (az/configuration)
          suffix fixture-suffix
          a-symbol (symbol (str "aguafria.composite-a-" suffix))
          b-symbol (symbol (str "aguafria.composite-b-" suffix))
          a-ns (create-ns a-symbol)
          b-ns (create-ns b-symbol)]
      (try
        (az/configure! {:async? true :modules {}})
        (doseq [target [a-ns b-ns]]
          (binding [*ns* target]
            (refer 'clojure.core)
            (alias 'az 'aguafria.zig)))
        (binding [*ns* a-ns]
          (eval '(az/defstruct Pair [[:x :i32] [:y :i32]]))
          (eval
           '(az/defn ^{:export false :public true} sum-pair :- :i32
              [pair :- Pair]
              (+ (az/field pair x) (az/field pair y))))
          (eval
           '(az/defn ^{:export false :public true :zig/qualifiers "!"}
              fallible :- :i32
              [x :- :i32]
              (+ x 1))))
        (binding [*ns* b-ns]
          (alias 'a a-symbol)
          (eval
           '(az/defn call-pair :- :i32
              [x :- :i32 y :- :i32]
              (a/sum-pair (a/Pair {:x x :y y}))))
          (eval
           '(az/defn call-fallible :- :i32
              [x :- :i32]
              (catch (a/fallible x) 0))))
        (is (= 7 ((ns-resolve b-ns 'call-pair) 3 4)))
        (is (= 4 ((ns-resolve b-ns 'call-fallible) 3)))
        (let [b-generation
              (->> (:declarations (az/stats b-symbol))
                   (some #(when (= "call-pair" (:name %))
                            (:implementation-generation %))))]
          (binding [*ns* a-ns]
            (eval
             '(az/defn ^{:export false :public true} sum-pair :- :i32
                [pair :- Pair]
                (+ (* (az/field pair x) 10) (az/field pair y))))
            (eval
             '(az/defn ^{:export false :public true :zig/qualifiers "!"}
                fallible :- :i32
                [x :- :i32]
                (+ x 10))))
          (az/await! a-symbol)
          (is (= 34 ((ns-resolve b-ns 'call-pair) 3 4)))
          (is (= 13 ((ns-resolve b-ns 'call-fallible) 3)))
          (is (= b-generation
                 (->> (:declarations (az/stats b-symbol))
                      (some #(when (= "call-pair" (:name %))
                               (:implementation-generation %)))))))
        (is (some #(= "sum_pair" (last (:logical-id %)))
                  (:dispatch-versions (az/stats a-symbol))))
        (finally
          (az/configure! old-config)
          (remove-ns b-symbol)
          (remove-ns a-symbol))))))

(deftest handwritten-cyclic-module-hot-reload-test
  (testing "ordinary Aguafria namespaces can form and hot-reload a Zig cycle"
    (let [old-config (az/configuration)
          suffix fixture-suffix
          a-symbol (symbol (str "aguafria.cyclic-a-" suffix))
          b-symbol (symbol (str "aguafria.cyclic-b-" suffix))
          a-ns (create-ns a-symbol)
          b-ns (create-ns b-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (doseq [target [a-ns b-ns]]
          (binding [*ns* target]
            (refer 'clojure.core)
            (alias 'az 'aguafria.zig)))
        (binding [*ns* a-ns]
          (alias 'b b-symbol)
          (eval
           '(az/defn ^{:export false :public true} inner-a :- :i32
              [x :- :i32]
              (+ x 1))))
        (binding [*ns* b-ns]
          (alias 'a a-symbol)
          (eval
           '(az/defn ^{:export false :public true} call-a :- :i32
              [x :- :i32]
              (+ (a/inner-a x) 2))))
        (binding [*ns* a-ns]
          (eval
           '(az/defn entry :- :i32
              [x :- :i32]
              (b/call-a x))))
        (is (= 8 ((ns-resolve a-ns 'entry) 5)))
        (let [caller-generation-before
              (->> (:declarations (az/stats a-symbol))
                   (some #(when (= "entry" (:name %))
                            (:implementation-generation %))))]
          (binding [*ns* a-ns]
            (eval
             '(az/defn ^{:export false :public true} inner-a :- :i32
                [x :- :i32]
                (+ x 10))))
          (is (= 17 ((ns-resolve a-ns 'entry) 5)))
          (let [caller-generation-after
                (->> (:declarations (az/stats a-symbol))
                     (some #(when (= "entry" (:name %))
                              (:implementation-generation %))))
                a-stats (az/stats a-symbol)
                b-stats (az/stats b-symbol)]
            (is (= caller-generation-before caller-generation-after))
            (is (:cyclic-dependency-component? a-stats))
            (is (:cyclic-dependency-component? b-stats))
            (is (= #{(str a-symbol) (str b-symbol)}
                   (set (:dependency-component-modules a-stats))))))
        (finally
          (az/configure! old-config)
          (remove-ns b-symbol)
          (remove-ns a-symbol))))))

(deftest cyclic-breaking-type-adoption-is-explicit-test
  (testing "cyclic callers retain a breaking type until each Var is reevaluated"
    (let [old-config (az/configuration)
          suffix fixture-suffix
          a-symbol (symbol (str "aguafria.cyclic-type-a-" suffix))
          b-symbol (symbol (str "aguafria.cyclic-type-b-" suffix))
          a-ns (create-ns a-symbol)
          b-ns (create-ns b-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (doseq [target [a-ns b-ns]]
          (binding [*ns* target]
            (refer 'clojure.core)
            (alias 'az 'aguafria.zig)
            (alias 'ak 'aguafria.keyword)))
        (binding [*ns* a-ns]
          (alias 'b b-symbol)
          (eval '(az/defstruct Item [[:value :u32]])))
        (binding [*ns* b-ns]
          (alias 'a a-symbol)
          (eval '(az/defn item-size :- :usize [] (ak/sizeOf a/Item))))
        (binding [*ns* a-ns]
          (eval '(az/defn entry :- :usize [] (b/item-size))))
        (is (= 4 ((ns-resolve a-ns 'entry))))
        (is (:cyclic-dependency-component? (az/stats a-symbol)))
        (is (some #(= (:abi-fingerprint %)
                      (:abi-fingerprint
                       (:aguafria/declaration
                        (meta (ns-resolve b-ns 'item-size)))))
                  (az/function-versions (ns-resolve b-ns 'item-size))))

        (az/configure! {:async? true :modules {}})
        (binding [*ns* a-ns]
          (eval '(az/defstruct Item [[:value :u32] [:extra :u32]])))
        (let [waiting (future (az/await! a-symbol))
              result (deref waiting 30000 ::timed-out)]
          (when (= ::timed-out result)
            (future-cancel waiting))
          (is (not= ::timed-out result)))
        (is (= 4 ((ns-resolve b-ns 'item-size))))
        (is (= 4 ((ns-resolve a-ns 'entry))))
        (binding [*ns* b-ns]
          (eval '(az/defn item-size :- :usize [] (ak/sizeOf a/Item))))
        (az/await! b-symbol)
        (is (= 8 ((ns-resolve b-ns 'item-size))))
        (is (= 4 ((ns-resolve a-ns 'entry))))
        (let [old-version
              (some #(when-not (:current? %) %)
                    (az/function-versions (ns-resolve b-ns 'item-size)))]
          (is (= 4 (az/invoke-version! (ns-resolve b-ns 'item-size)
                                       (:abi-fingerprint old-version) []))))
        (binding [*ns* a-ns]
          (eval '(az/defn entry :- :usize [] (b/item-size))))
        (az/await! a-symbol)
        (is (= 8 ((ns-resolve a-ns 'entry))))
        (is (= [:retained :breaking]
               (mapv :status
                     (az/type-versions (ns-resolve a-ns 'Item)))))
        (finally
          (az/configure! old-config)
          (remove-ns b-symbol)
          (remove-ns a-symbol))))))

(deftest cyclic-compatible-type-propagation-does-not-self-await-test
  (testing "a compatible type-factory edit atomically propagates through its SCC"
    (let [old-config (az/configuration)
          suffix fixture-suffix
          a-symbol (symbol (str "aguafria.cyclic-factory-a-" suffix))
          b-symbol (symbol (str "aguafria.cyclic-factory-b-" suffix))
          a-ns (create-ns a-symbol)
          b-ns (create-ns b-symbol)
          define-options!
          (fn [answer]
            (binding [*ns* a-ns]
              (eval
               (clojure.walk/postwalk
                #(if (= 'aguafria-test/answer %) answer %)
                '(az/defn OptionsType {:attrs #{:public}} :- :type []
                   (ak/return
                    (az/container
                     {:kind :struct :layout :normal}
                     (az/field-decl value :u32)
                     (az/fn-decl answer {:attrs #{:public}} :- :u32 []
                       (ak/return aguafria-test/answer)))))))))]
      (try
        (az/configure! {:async? false :modules {}})
        (doseq [target [a-ns b-ns]]
          (binding [*ns* target]
            (refer 'clojure.core)
            (alias 'az 'aguafria.zig)
            (alias 'ak 'aguafria.keyword)))
        (binding [*ns* a-ns] (alias 'b b-symbol))
        (define-options! 1)
        (binding [*ns* b-ns]
          (alias 'a a-symbol)
          (eval
           '(az/defn option-answer :- :u32 []
              ((az/field (a/OptionsType) answer)))))
        (binding [*ns* a-ns]
          (eval '(az/defn entry :- :u32 [] (b/option-answer))))
        (is (= 1 ((ns-resolve a-ns 'entry))))
        (is (:cyclic-dependency-component? (az/stats a-symbol)))

        (az/configure! {:async? true :modules {}})
        (define-options! 2)
        (let [waiting (future (az/await! a-symbol))
              result (deref waiting 30000 ::timed-out)]
          (when (= ::timed-out result)
            (future-cancel waiting))
          (is (not= ::timed-out result)))
        (is (= 2 ((ns-resolve b-ns 'option-answer))))
        (is (= 2 ((ns-resolve a-ns 'entry))))
        (is (= [:compatible]
               (mapv :status
                     (az/type-versions (ns-resolve a-ns 'OptionsType)))))
        (finally
          (az/configure! old-config)
          (remove-ns b-symbol)
          (remove-ns a-symbol))))))

(deftest comptime-type-factory-alias-is-a-constructor-test
  (testing "a const initialized by a type factory is known as a real Zig type"
    (let [old-config (az/configuration)
          module-symbol (symbol (str "aguafria.factory-constructor-"
                                     fixture-suffix))
          module-ns (create-ns module-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* module-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval
           '(az/defn ColorType
              {:attrs #{:public :implicit-return}}
              :- :type
              []
              (az/container
               {:kind :struct :layout :extern}
               (az/field-decl r :f32)
               (az/field-decl g :f32))))
          (eval '(az/defconst Color {:attrs #{:public}} (ColorType)))
          (eval
           '(az/defn sum-color :- :f32
              [[color Color]]
              (+ (az/field color r) (az/field color g))))
          (eval
           '(az/defn constructed-sum :- :f32
              []
              (sum-color (Color {:r 1.25 :g 2.5})))))
        (let [constructor @(ns-resolve module-ns 'Color)
              color (constructor {:r 1.25 :g 2.5})]
          (is (az/zig-type? constructor))
          (is (= {:r 1.25 :g 2.5} (az/value color)))
          (is (= 3.75 ((ns-resolve module-ns 'sum-color) color)))
          (is (= 3.75 ((ns-resolve module-ns 'constructed-sum)))))
        (is (str/includes? (az/source module-symbol)
                           "Color{.g = 2.5, .r = 1.25}"))
        (finally
          (az/configure! old-config)
          (remove-ns module-symbol))))))

(deftest composite-return-materializes-through-a-complete-wrapper-generation-test
  (testing "a native result requests a full-module JVM trampoline"
    (let [old-config (az/configuration)
          module-symbol (symbol (str "aguafria.composite-return-"
                                     fixture-suffix))
          module-ns (create-ns module-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* module-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval
           '(az/defstruct Snapshot
              {:layout :extern}
              [[:count :u32]
               [:ready :bool]]))
          (eval
           '(az/defn snapshot
              :- Snapshot
              []
              (Snapshot {:count 7 :ready true}))))
        (let [snapshot ((ns-resolve module-ns 'snapshot))]
          (is (az/zig-value? snapshot))
          (is (= {:count 7 :ready true} (az/value snapshot))))
        (finally
          (az/configure! old-config)
          (remove-ns module-symbol))))))

(deftest cyclic-component-atomic-publication-and-rollback-test
  (testing "every prepared SCC member publishes together and a failed prepare publishes none"
    (let [old-config (az/configuration)
          suffix fixture-suffix
          a-symbol (symbol (str "aguafria.atomic-a-" suffix))
          b-symbol (symbol (str "aguafria.atomic-b-" suffix))
          a-ns (create-ns a-symbol)
          b-ns (create-ns b-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (doseq [target [a-ns b-ns]]
          (binding [*ns* target]
            (refer 'clojure.core)
            (alias 'az 'aguafria.zig)))
        (binding [*ns* a-ns]
          (alias 'b b-symbol)
          (eval
           '(az/defn ^{:export false :public true} inner-a :- :i32
              [x :- :i32]
              (+ x 1))))
        (binding [*ns* b-ns]
          (alias 'a a-symbol)
          (eval
           '(az/defn ^{:export false :public true} call-a :- :i32
              [x :- :i32]
              (+ (a/inner-a x) 2))))
        (binding [*ns* a-ns]
          (eval
           '(az/defn entry :- :i32
              [x :- :i32]
              (b/call-a x))))
        (is (= 8 ((ns-resolve a-ns 'entry) 5)))

        (let [good-a
              (capture-declaration
               a-ns
               '(az/defn ^{:export false :public true} inner-a :- :i32
                  [x :- :i32]
                  (+ x 10)))
              good-b
              (capture-declaration
               b-ns
               '(az/defn ^{:export false :public true} call-a :- :i32
                  [x :- :i32]
                  (+ (a/inner-a x) 5)))]
          (runtime/register-batch! [good-a]
                                   {:module a-symbol :compile? false
                                    :replace? false})
          (runtime/register-batch! [good-b]
                                   {:module b-symbol :compile? false
                                    :replace? false})
          ;; Source/Vars are inspectable immediately, but native publication
          ;; remains on the previous complete component until requested.
          (is (= 8 ((ns-resolve a-ns 'entry) 5)))
          (let [publication (az/recompile-component! a-symbol)
                a-stats (az/stats a-symbol)
                b-stats (az/stats b-symbol)
                publication-id (:component publication)]
            (is (= #{(str a-symbol) (str b-symbol)}
                   (set (:modules publication))))
            (is (nat-int? (:duration-ms publication)))
            (is (= (:duration-ms publication)
                   (:critical-path-ms publication)))
            (is (= 20 ((ns-resolve a-ns 'entry) 5)))
            (is (= publication-id
                   (get-in a-stats [:last-component-publication :id])))
            (is (= publication-id
                   (get-in b-stats [:last-component-publication :id])))
            (is (= (:modules publication)
                   (get-in a-stats [:last-component-publication :modules])))

            (let [published-before
                  {a-symbol (:published-generation a-stats)
                   b-symbol (:published-generation b-stats)}
                  bad-a
                  (capture-declaration
                   a-ns
                   '(az/defn ^{:export false :public true} inner-a :- :i32
                      [x :- :i32]
                      (+ x true)))
                  next-b
                  (capture-declaration
                   b-ns
                   '(az/defn ^{:export false :public true} call-a :- :i32
                      [x :- :i32]
                      (+ (a/inner-a x) 7)))]
              (runtime/register-batch! [bad-a]
                                       {:module a-symbol :compile? false
                                        :replace? false})
              (runtime/register-batch! [next-b]
                                       {:module b-symbol :compile? false
                                        :replace? false})
              (let [failure
                    (try
                      (az/recompile-component! a-symbol)
                      nil
                      (catch clojure.lang.ExceptionInfo error error))
                    a-failed (az/stats a-symbol)
                    b-failed (az/stats b-symbol)]
                (is (instance? clojure.lang.ExceptionInfo failure))
                (is (= published-before
                       {a-symbol (:published-generation a-failed)
                        b-symbol (:published-generation b-failed)}))
                (is (= 20 ((ns-resolve a-ns 'entry) 5)))
                (is (string? (get-in a-failed
                                     [:last-component-publication-failure
                                      :error])))
                (is (= (get-in a-failed
                               [:last-component-publication-failure :component])
                       (get-in b-failed
                               [:last-component-publication-failure :component]))))

              ;; Restore the last valid descriptors through the same component
              ;; recovery path so this test leaves no failed native state.
              (runtime/register-batch! [good-a]
                                       {:module a-symbol :compile? false
                                        :replace? false})
              (runtime/register-batch! [good-b]
                                       {:module b-symbol :compile? false
                                        :replace? false})
              (az/recompile-component! a-symbol)
              (is (= 20 ((ns-resolve a-ns 'entry) 5))))))
        (finally
          (az/configure! old-config)
          (remove-ns b-symbol)
          (remove-ns a-symbol))))))

(deftest cross-namespace-active-call-survives-callee-swap-test
  (testing "a caller-library invocation keeps the old callee library alive through publication"
    (let [old-config (az/configuration)
          suffix fixture-suffix
          a-symbol (symbol (str "aguafria.active-live-a-" suffix))
          b-symbol (symbol (str "aguafria.active-live-b-" suffix))
          a-ns (create-ns a-symbol)
          b-ns (create-ns b-symbol)
          worker (atom nil)]
      (try
        (az/configure! {:async? false :modules {}})
        (doseq [target [a-ns b-ns]]
          (binding [*ns* target]
            (refer 'clojure.core)
            (alias 'az 'aguafria.zig)
            (alias 'ak 'aguafria.keyword)))
        (binding [*ns* a-ns]
          (eval
           '(az/defn spin :- :u64
              [n :- :u64]
              (ak/var i :u64 0)
              (while (< i n)
                (+= i 1))
              i)))
        (binding [*ns* b-ns]
          (alias 'a a-symbol)
          (eval
           '(az/defn call-spin :- :u64
              [n :- :u64]
              (a/spin n))))
        (let [b-generation-before
              (->> (:declarations (az/stats b-symbol))
                   (some #(when (= "call-spin" (:name %))
                            (:implementation-generation %))))]
          (reset! worker
                  (future ((ns-resolve b-ns 'call-spin) 5000000000)))
          (is (loop [attempt 0]
                (cond
                  (some #(pos? (:native-active-call-count %))
                        (:native-generations (az/stats a-symbol))) true
                  (< attempt 500) (do (Thread/sleep 10)
                                      (recur (inc attempt)))
                  :else false))
              "the imported A implementation should report the native call")
          (binding [*ns* a-ns]
            (eval
             '(az/defn spin :- :u64
                [n :- :u64]
                (ak/var i :u64 0)
                (while (< i n)
                  (+= i 1))
                (+ i 1))))
          (let [during (az/stats a-symbol)
                b-generation-after
                (->> (:declarations (az/stats b-symbol))
                     (some #(when (= "call-spin" (:name %))
                              (:implementation-generation %))))]
            (is (some :retirement-pending? (:native-generations during)))
            (is (= b-generation-before b-generation-after))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Cannot clear Aguafria while native calls are active"
                                  (az/clear!))))
          (is (= 5000000000 (deref @worker 30000 ::timed-out)))
          (is (= 11 ((ns-resolve b-ns 'call-spin) 10)))
          (is (pos? (:retired-generation-count (az/stats a-symbol)))))
          (finally
            (when (and @worker (not (realized? @worker)))
              (deref @worker 30000 ::timed-out))
            (az/configure! old-config)
            (remove-ns b-symbol)
            (remove-ns a-symbol))))))

(deftest quiescent-native-generation-retirement-test
  (testing "an obsolete shared library is unloaded after its calls and implementations quiesce"
    (let [old-config (az/configuration)
          module (str "aguafria.retirement-fixture-" fixture-suffix)
          qualified-name (symbol module "probe")
          declaration
          (fn [body]
            {:kind :fn
             :name 'probe
             :qualified-name qualified-name
             :declaration-key [:fn 'probe]
             :module module
             :args [{:name 'x :type :i32 :properties {}}]
             :return :i32
             :body body
             :export? true
             :public? true
             :implicit-return? true
             :source {:file "test/aguafria/zig_integration_test.clj"
                      :line (:line (meta #'quiescent-native-generation-retirement-test))
                      :column 1}})]
      (try
        (az/configure! {:async? false :modules {}})
        (runtime/register-declaration! (declaration ['x]))
        (is (= 7 (runtime/invoke! qualified-name [7])))
        (is (= 1 (:native-generation-count (az/stats module))))

        (runtime/register-declaration!
         (declaration [(list '+ 'x 1)]))
        (is (= 8 (runtime/invoke! qualified-name [7])))
        (let [module-stats (az/stats module)
              retired (last (:retired-generations module-stats))]
          (is (= 1 (:native-generation-count module-stats)))
          (is (= 1 (:retired-generation-count module-stats)))
          (is (zero? (:native-active-call-count retired)))
          (is (zero? (:jvm-active-call-count retired))))
        (finally
          (az/configure! old-config))))))

(deftest active-native-call-survives-publication-test
  (testing "publishing a replacement retains an old library until its in-flight call returns"
    (let [old-config (az/configuration)
          module (str "aguafria.active-call-fixture-" fixture-suffix)
          qualified-name (symbol module "spin")
          worker (atom nil)
          declaration
          (fn [offset]
            {:kind :fn
             :name 'spin
             :qualified-name qualified-name
             :declaration-key [:fn 'spin]
             :module module
             :args [{:name 'n :type :u64 :properties {}}]
             :return :u64
             :body [(list 'var 'i :u64 0)
                    (list 'while (list '< 'i 'n)
                          (list '+= 'i 1))
                    (if (zero? offset) 'i (list '+ 'i offset))]
             :export? true
             :public? true
             :implicit-return? true
             :source {:file "test/aguafria/zig_integration_test.clj"
                      :line (:line (meta #'active-native-call-survives-publication-test))
                      :column 1}})
          active?
          (fn []
            (some #(pos? (:native-active-call-count %))
                  (:native-generations (az/stats module))))]
      (try
        (az/configure! {:async? false :modules {}})
        (runtime/register-declaration! (declaration 0))
        (reset! worker (future (runtime/invoke! qualified-name [5000000000])))
        (is (loop [attempt 0]
              (cond
                (active?) true
                (< attempt 500) (do (Thread/sleep 10) (recur (inc attempt)))
                :else false))
            "the test call should enter native code")

        (runtime/register-declaration! (declaration 1))
        (let [during (az/stats module)]
          (is (= 2 (:native-generation-count during)))
          (is (some :retirement-pending? (:native-generations during))))

        (is (= 5000000000 (deref @worker 30000 ::timed-out)))
        (is (= 11 (runtime/invoke! qualified-name [10])))
        (let [after (az/stats module)]
          (is (= 1 (:native-generation-count after)))
          (is (= 1 (:retired-generation-count after))))
        (finally
          (when (and @worker (not (realized? @worker)))
            (deref @worker 30000 ::timed-out))
          (az/configure! old-config))))))

(deftest callable-abi-version-coexistence-test
  (testing "a breaking signature publishes v2 while v1 remains callable"
    (let [old-config (az/configuration)
          module (str "aguafria.abi-version-fixture-" fixture-suffix)
          qualified-name (symbol module "calculate")
          declaration
          (fn [args body]
            {:kind :fn
             :name 'calculate
             :qualified-name qualified-name
             :declaration-key [:fn 'calculate]
             :module module
             :args args
             :return :i32
             :body body
             :export? true
             :public? true
             :implicit-return? true
             :source {:file "test/aguafria/zig_integration_test.clj"
                      :line (:line (meta #'callable-abi-version-coexistence-test))
                      :column 1}})
          v1 (runtime/declaration-info
              (declaration [{:name 'x :type :i32 :properties {}}]
                           [(list '+ 'x 1)]))
          v2 (runtime/declaration-info
              (declaration [{:name 'x :type :i32 :properties {}}
                            {:name 'y :type :i32 :properties {}}]
                           [(list '+ 'x 'y)]))]
      (try
        (az/configure! {:async? false :modules {}})
        (runtime/register-declaration! v1)
        (is (= 8 (runtime/invoke! qualified-name [7])))

        (runtime/register-declaration! v2)
        (is (= 12 (runtime/invoke! qualified-name [5 7])))
        (is (= 8 (az/invoke-version! qualified-name
                                     (:abi-fingerprint v1) [7])))
        (let [versions (az/function-versions qualified-name)
              by-abi (into {} (map (juxt :abi-fingerprint identity)) versions)
              module-stats (az/stats module)]
          (is (= 2 (count versions)))
          (is (false? (get-in by-abi [(:abi-fingerprint v1) :current?])))
          (is (true? (get-in by-abi [(:abi-fingerprint v2) :current?])))
          (is (= 2 (:native-generation-count module-stats)))
          (is (= 2 (:dispatch-version-count module-stats))))
        (finally
          (az/configure! old-config))))))

(deftest breaking-callee-keeps-old-caller-live-test
  (testing "a breaking A publishes independently while old B keeps calling A v1"
    (let [old-config (az/configuration)
          module (str "aguafria.breaking-caller-fixture-" fixture-suffix)
          a-name (symbol module "a")
          b-name (symbol module "b")
          declaration
          (fn [name args body]
            {:kind :fn
             :name name
             :qualified-name (symbol module (str name))
             :declaration-key [:fn name]
             :module module
             :args args
             :return :i32
             :body body
             :export? true
             :public? true
             :implicit-return? true
             :source {:file "test/aguafria/zig_integration_test.clj"
                      :line (:line (meta #'breaking-callee-keeps-old-caller-live-test))
                      :column 1}})
          arg-x {:name 'x :type :i32 :properties {}}
          arg-y {:name 'y :type :i32 :properties {}}
          a-v1 (runtime/declaration-info
                (declaration 'a [arg-x] [(list '+ 'x 1)]))
          a-v2 (runtime/declaration-info
                (declaration 'a [arg-x arg-y] [(list '+ 'x 'y)]))
          b-v1 (declaration 'b [arg-x]
                            [(list '+ (list 'a 'x) 100)])
          b-v2 (declaration 'b [arg-x]
                            [(list '+ (list 'a 'x 10) 100)])]
      (try
        (az/configure! {:async? true :modules {}})
        (runtime/register-declaration! a-v1)
        (runtime/register-declaration! b-v1)
        (is (= 106 (runtime/invoke! b-name [5])))
        (let [b-generation-before
              (->> (:declarations (az/stats module))
                   (some #(when (= "b" (:name %))
                            (:implementation-generation %))))]
          (runtime/register-declaration! a-v2)
          (az/await! module)
          (let [partial-stats (az/stats module)
                b-generation-after
                (->> (:declarations partial-stats)
                     (some #(when (= "b" (:name %))
                              (:implementation-generation %))))]
            (is (:partial-publication? partial-stats))
            (is (string? (:full-compile-error partial-stats)))
            (is (= b-generation-before b-generation-after)))

          (is (= 12 (runtime/invoke! a-name [5 7])))
          (is (= 106 (runtime/invoke! b-name [5])))
          (is (= 6 (az/invoke-version! a-name
                                      (:abi-fingerprint a-v1) [5])))

          (runtime/register-declaration! b-v2)
          (is (= 115 (runtime/invoke! b-name [5])))
          (is (false? (:partial-publication? (az/stats module)))))
        (finally
          (az/configure! old-config))))))

(deftest breaking-callable-live-slice-closes-over-cross-namespace-dependencies-test
  (testing "a breaking Var publishes with local and imported dependencies while old callers remain live"
    (let [old-config (az/configuration)
          suffix fixture-suffix
          dependency-symbol (symbol (str "aguafria.breaking-dependency-" suffix))
          callee-symbol (symbol (str "aguafria.breaking-callee-" suffix))
          caller-symbol (symbol (str "aguafria.breaking-dependent-" suffix))
          dependency-ns (create-ns dependency-symbol)
          callee-ns (create-ns callee-symbol)
          caller-ns (create-ns caller-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (doseq [target [dependency-ns callee-ns caller-ns]]
          (binding [*ns* target]
            (refer 'clojure.core)
            (alias 'az 'aguafria.zig)))
        (binding [*ns* dependency-ns]
          (eval
           '(az/defn ^{:export false :public true} combine :- :i32
              [x :- :i32 y :- :i32]
              (* x y))))
        (binding [*ns* callee-ns]
          (alias 'dependency dependency-symbol)
          (eval
           '(az/defn a :- :i32
              [x :- :i32]
              (+ x 1)))
          (eval
           '(az/defn old-local-caller :- :i32
              [x :- :i32]
              (+ (a x) 10))))
        (binding [*ns* caller-ns]
          (alias 'callee callee-symbol)
          (eval
           '(az/defn remote-caller :- :i32
              [x :- :i32]
              (+ (callee/old-local-caller x) 100))))

        (is (= 116 ((ns-resolve caller-ns 'remote-caller) 5)))
        (let [a-v1-abi (-> (ns-resolve callee-ns 'a)
                           meta :aguafria/declaration :abi-fingerprint)
              remote-generation-before
              (->> (:declarations (az/stats caller-symbol))
                   (some #(when (= "remote-caller" (:name %))
                            (:implementation-generation %))))]
          ;; `a@v2` needs both a newly added local const and an imported
          ;; Zig-only helper. The stale one-argument local caller makes the
          ;; complete callee module invalid, forcing the breaking live slice.
          (binding [*ns* callee-ns]
            (eval '(az/defconst local-bias-base :i32 2))
            (eval '(az/defconst local-bias :i32 (+ local-bias-base 1)))
            (eval
             '(az/defn a :- :i32
                [x :- :i32 y :- :i32]
                (dependency/combine (+ x local-bias) y))))

          (is (= 20 ((ns-resolve callee-ns 'a) 2 4)))
          (is (= 16 ((ns-resolve callee-ns 'old-local-caller) 5)))
          (is (= 116 ((ns-resolve caller-ns 'remote-caller) 5)))
          (is (= 6 (az/invoke-version! (ns-resolve callee-ns 'a)
                                       a-v1-abi [5])))
          (let [partial-stats (az/stats callee-symbol)]
            (is (:partial-publication? partial-stats))
            (is (string? (:full-compile-error partial-stats)))
            (is (some #{(str dependency-symbol)}
                      (:dependencies partial-stats))))

          ;; Explicitly reevaluating the stale local caller adopts `a@v2`.
          ;; Its ABI is compatible, so the untouched remote caller follows the
          ;; dispatch swap without being recompiled.
          (binding [*ns* callee-ns]
            (eval
             '(az/defn old-local-caller :- :i32
                [x :- :i32]
                (+ (a x 4) 10))))
          (is (= 142 ((ns-resolve caller-ns 'remote-caller) 5)))
          (is (= remote-generation-before
                 (->> (:declarations (az/stats caller-symbol))
                      (some #(when (= "remote-caller" (:name %))
                               (:implementation-generation %))))))
          (is (= 2 (count (az/function-versions
                           (ns-resolve callee-ns 'a)))))
          (is (false? (:partial-publication? (az/stats callee-symbol)))))
        (finally
          (az/configure! old-config)
          (remove-ns caller-symbol)
          (remove-ns callee-symbol)
          (remove-ns dependency-symbol))))))

(deftest live-defvar-state-preservation-and-explicit-migration-test
  (testing "compatible reloads preserve state and breaking layouts wait for explicit Zig migration"
    (let [old-config (az/configuration)
          suffix fixture-suffix
          test-symbol (symbol (str "aguafria.live-state-" suffix))
          test-ns (create-ns test-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (alias 'ak 'aguafria.keyword)
          (eval '(az/defvar counter :i32 1))
          (eval '(az/defn read-counter :- :i32 [] counter))
          (eval '(az/defn write-counter :- :void
                   [value :- :i32]
                   (az/assign "=" counter value)))
          (eval '(az/defn migrate-counter :- :void
                   [old-address :- :usize new-address :- :usize]
                   (ak/const old-value [:*const :i32]
                     (ak/ptrFromInt old-address))
                   (ak/const new-value [:* :i64]
                     (ak/ptrFromInt new-address))
                   (az/assign "=" (az/deref new-value)
                     (ak/intCast (az/deref old-value))))))
        ((ns-resolve test-ns 'write-counter) 37)
        (is (= 37 ((ns-resolve test-ns 'read-counter))))

        (binding [*ns* test-ns]
          (eval '(az/defn read-counter :- :i32 [] (+ counter 1))))
        (is (= 38 ((ns-resolve test-ns 'read-counter))))
        (is (= :preserved (:status (last (az/state-versions
                                          (ns-resolve test-ns 'counter))))))

        (let [layout-error
              (binding [*ns* test-ns]
                (try
                  (eval '(az/defvar counter :i64 0))
                  nil
                  (catch clojure.lang.ExceptionInfo error error)))]
          (is (= :zig-state-migration-required
                 (:aguafria/phase (ex-data layout-error))))
          (is (= 38 ((ns-resolve test-ns 'read-counter)))))

        (binding [*ns* test-ns]
          (let [reader-error
                (try
                  (eval '(az/defn read-counter :- :i64 [] counter))
                  nil
                  (catch clojure.lang.ExceptionInfo error error))]
            (is (= :zig-state-migration-required
                   (:aguafria/phase (ex-data reader-error))))))

        (let [publication
              (az/migrate-state!
               (symbol (str test-symbol) "counter")
               (symbol (str test-symbol) "migrate-counter"))
              versions (az/state-versions
                        (symbol (str test-symbol) "counter"))
              active (some #(when (:active? %) %) versions)
              retained (filter #(= :retained (:status %)) versions)]
          (is (integer? (:component publication)))
          (is (= 37 ((ns-resolve test-ns 'read-counter))))
          (is (= :migrated (:status active)))
          (is (= :i64
                 (:type (some #(when (= 'counter (:name %)) %)
                              (:definitions (az/module-info test-symbol))))))
          (is (seq retained))
          (is (= (:migration-function active)
                 (get-in publication [:migration :function]))))
        (is (some #(= :migration-required (:status %))
                  (:builds (az/stats test-symbol))))
        (is (not (str/includes? (az/source test-symbol)
                                "__aguafria_state_")))
        (finally
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest versioned-defstruct-layout-and-dependent-reevaluation-test
  (testing "breaking layouts coexist and an explicitly reevaluated dependent adopts the new schema"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.live-type-" fixture-suffix))
          test-ns (create-ns test-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (alias 'ak 'aguafria.keyword)
          (eval '(az/defstruct Item [[:value :i32]]))
          (eval '(az/defn item-size :- :usize [] (ak/sizeOf Item))))
        (is (= 4 ((ns-resolve test-ns 'item-size))))

        (binding [*ns* test-ns]
          (eval '(az/defstruct Item [[:value :i32] [:extra :i32]])))
        (let [versions (az/type-versions (ns-resolve test-ns 'Item))]
          (is (= 2 (count versions)))
          (is (= :retained (:status (first versions))))
          (is (= :breaking (:status (last versions))))
          (is (not= (:schema-fingerprint (first versions))
                    (:schema-fingerprint (last versions)))))
        ;; The previous dependent stays live until the user reevaluates it.
        (is (= 4 ((ns-resolve test-ns 'item-size))))
        (binding [*ns* test-ns]
          (eval '(az/defn item-size :- :usize [] (ak/sizeOf Item))))
        (is (= 8 ((ns-resolve test-ns 'item-size))))
        (finally
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest cross-namespace-type-factory-hot-propagation-test
  (testing "type-factory edits automatically republish existing monomorphized callers"
    (let [old-config (az/configuration)
          suffix fixture-suffix
          type-symbol (symbol (str "aguafria.live-type-factory-" suffix))
          caller-symbol (symbol (str "aguafria.live-type-caller-" suffix))
          type-ns (create-ns type-symbol)
          caller-ns (create-ns caller-symbol)
          pre-break-answer-abi (atom nil)
          define-options!
          (fn [field-type answer]
            (binding [*ns* type-ns]
              (eval
               (clojure.walk/postwalk
                (fn [value]
                  (case value
                    aguafria-test/field-type field-type
                    aguafria-test/answer-value answer
                    value))
                '(az/defn OptionsType {:attrs #{:public}} :- :type []
                   (ak/return
                    (az/container
                     {:kind :struct :layout :normal}
                     (az/field-decl value aguafria-test/field-type)
                     (az/fn-decl answer
                       {:attrs #{:public}}
                       :- :u32 []
                       (ak/return aguafria-test/answer-value)))))))))]
      (try
        (az/configure! {:async? true :modules {}})
        (doseq [target [type-ns caller-ns]]
          (binding [*ns* target]
            (refer 'clojure.core)
            (alias 'az 'aguafria.zig)
            (alias 'ak 'aguafria.keyword)))
        (define-options! :u32 1)
        (az/await! type-symbol)
        (binding [*ns* caller-ns]
          (alias 'types type-symbol)
          (eval
           '(az/defn option-size :- :usize []
              (ak/return (ak/sizeOf (types/OptionsType)))))
          (eval
           '(az/defn option-answer :- :u32 []
              (ak/return ((az/field (types/OptionsType) answer))))))
        (is (= 4 ((ns-resolve caller-ns 'option-size))))
        (is (= 1 ((ns-resolve caller-ns 'option-answer))))

        (let [before-generation
              (:published-generation (az/module-info caller-symbol))]
          (define-options! :u32 2)
          (az/await! type-symbol)
          (is (= 2 ((ns-resolve caller-ns 'option-answer))))
          (is (< before-generation
                 (:published-generation (az/module-info caller-symbol))))
          (reset! pre-break-answer-abi
                  (:abi-fingerprint
                   (some #(when (:current? %) %)
                         (az/function-versions
                          (ns-resolve caller-ns 'option-answer))))))

        (let [before-generation
              (:published-generation (az/module-info caller-symbol))]
          (define-options! :u64 3)
          (az/await! type-symbol)
          ;; Breaking type generations do not silently redirect untouched
          ;; callers. They continue through the retained monomorphization.
          (is (= 4 ((ns-resolve caller-ns 'option-size))))
          (is (= 2 ((ns-resolve caller-ns 'option-answer))))
          (is (= 2 (az/invoke-version!
                    (ns-resolve caller-ns 'option-answer)
                    @pre-break-answer-abi [])))
          (is (= before-generation
                 (:published-generation (az/module-info caller-symbol))))
          (is (= [:retained :breaking]
                 (mapv :status
                       (az/type-versions
                        (ns-resolve type-ns 'OptionsType)))))

          (binding [*ns* caller-ns]
            (eval
             '(az/defn option-size :- :usize []
                (ak/return (ak/sizeOf (types/OptionsType)))))
            (eval
             '(az/defn option-answer :- :u32 []
                (ak/return ((az/field (types/OptionsType) answer))))))
          (az/await! caller-symbol)
          (is (= 8 ((ns-resolve caller-ns 'option-size))))
          (is (= 3 ((ns-resolve caller-ns 'option-answer)))))

        ;; A compatible method edit on the new layout must not erase the
        ;; retained pre-break schema or downgrade the active lineage.
        (define-options! :u64 4)
        (az/await! type-symbol)
        (is (= 4 ((ns-resolve caller-ns 'option-answer))))
        (is (= [:retained :breaking]
               (mapv :status
                     (az/type-versions
                      (ns-resolve type-ns 'OptionsType)))))
        (finally
          (az/configure! old-config)
          (remove-ns caller-symbol)
          (remove-ns type-symbol))))))

(deftest cross-namespace-type-factory-state-migration-test
  (testing "a breaking factory type retains dependent state until an explicit migration"
    (let [old-config (az/configuration)
          suffix fixture-suffix
          type-symbol (symbol (str "aguafria.state-type-factory-" suffix))
          state-symbol (symbol (str "aguafria.state-type-owner-" suffix))
          type-ns (create-ns type-symbol)
          state-ns (create-ns state-symbol)
          define-options!
          (fn [field-type]
            (binding [*ns* type-ns]
              (eval
               (clojure.walk/postwalk
                (fn [value]
                  (if (= value 'aguafria-test/field-type)
                    field-type
                    value))
                '(az/defn OptionsType {:attrs #{:public}} :- :type []
                   (ak/return
                    (az/container
                     {:kind :struct :layout :normal}
                     (az/field-decl value aguafria-test/field-type))))))))]
      (try
        (az/configure! {:async? true :modules {}})
        (doseq [target [type-ns state-ns]]
          (binding [*ns* target]
            (refer 'clojure.core)
            (alias 'az 'aguafria.zig)
            (alias 'ak 'aguafria.keyword)))
        (define-options! :u32)
        (az/await! type-symbol)
        (binding [*ns* state-ns]
          (alias 'types type-symbol)
          (eval '(az/defstruct OldOptions [[:value :u32]]))
          (eval
           '(az/defvar options (types/OptionsType)
              (az/object [[:value 11]])))
          (eval
           '(az/defn read-option :- :u64 []
              (ak/return (ak/intCast (az/field options value)))))
          ;; This declaration is compiled against the old factory type first.
          ;; It is explicitly reevaluated after the type break so the user,
          ;; rather than automatic propagation, chooses the migration edge.
          (eval
           '(az/defn migrate-options :- :void
              [old-address :- :usize new-address :- :usize]
              (ak/const old-options [:*const OldOptions]
                (ak/ptrFromInt old-address))
              (ak/const new-options [:* (types/OptionsType)]
                (ak/ptrFromInt new-address))
              (set! (az/field (az/deref new-options) value)
                (ak/intCast
                 (az/field (az/deref old-options) value))))))
        (is (= 11 ((ns-resolve state-ns 'read-option))))

        (define-options! :u64)
        (is (map? (az/await! type-symbol)))
        (is (= 11 ((ns-resolve state-ns 'read-option))))
        (is (= [:retained :breaking]
               (mapv :status
                     (az/type-versions
                      (symbol (str type-symbol) "OptionsType")))))

        (binding [*ns* state-ns]
          (eval
           '(az/defvar options (types/OptionsType)
              (az/object [[:value 11]])))
          (eval
           '(az/defn migrate-options :- :void
              [old-address :- :usize new-address :- :usize]
              (ak/const old-options [:*const OldOptions]
                (ak/ptrFromInt old-address))
              (ak/const new-options [:* (types/OptionsType)]
                (ak/ptrFromInt new-address))
              (set! (az/field (az/deref new-options) value)
                (ak/intCast
                 (az/field (az/deref old-options) value))))))
        (let [migration-required
              (try
                (az/await! state-symbol)
                nil
                (catch clojure.lang.ExceptionInfo error error))]
          (is (= :zig-state-migration-required
                 (:aguafria/phase (ex-data migration-required))))
          (is (false? (:pending? (az/module-info state-symbol))))
          ;; A late await still reports the completed state generation's
          ;; migration requirement; the previously published capsule remains
          ;; callable throughout.
          (is (= :zig-state-migration-required
                 (:aguafria/phase
                  (ex-data
                   (try
                     (az/await! state-symbol)
                     nil
                     (catch clojure.lang.ExceptionInfo error error))))))
          (is (= 11 ((ns-resolve state-ns 'read-option)))))

        (az/migrate-state!
         (symbol (str state-symbol) "options")
         (symbol (str state-symbol) "migrate-options"))
        (is (map? (az/await! state-symbol)))
        (is (= 11 ((ns-resolve state-ns 'read-option))))
        (is (= [:retained :migrated]
               (mapv :status
                     (az/state-versions
                      (symbol (str state-symbol) "options")))))
        (finally
          (az/configure! old-config)
          (remove-ns state-symbol)
          (remove-ns type-symbol))))))

(deftest struct-backed-state-explicit-migration-test
  (testing "a breaking defstruct-backed capsule retains old state and migrates without reinterpretation"
    (let [old-config (az/configuration)
          test-symbol (symbol (str "aguafria.struct-state-" fixture-suffix))
          test-ns (create-ns test-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* test-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (alias 'ak 'aguafria.keyword)
          (eval '(az/defstruct OldCounterState [[:value :i32]]))
          (eval '(az/defstruct CounterState [[:value :i32]]))
          (eval '(az/defvar state CounterState
                   (CounterState {:value 1})))
          (eval '(az/defn read-value :- :i32 []
                   (az/field state value)))
          (eval '(az/defn write-value :- :void
                   [value :- :i32]
                   (az/assign "=" (az/field state value) value))))
        ((ns-resolve test-ns 'write-value) 41)

        ;; The type can publish as an independent retained live slice while
        ;; state and existing code continue on the old schema.
        (binding [*ns* test-ns]
          (eval '(az/defstruct CounterState
                   [[:value :i32] [:extra :i32]])))
        (is (= 41 ((ns-resolve test-ns 'read-value))))
        (is (= [:retained :breaking]
               (mapv :status
                     (az/type-versions (ns-resolve test-ns 'CounterState)))))

        (let [state-error
              (binding [*ns* test-ns]
                (try
                  (eval '(az/defvar state CounterState
                           (CounterState {:value 0 :extra 0})))
                  nil
                  (catch clojure.lang.ExceptionInfo error error)))]
          (is (= :zig-state-migration-required
                 (:aguafria/phase (ex-data state-error))))
          (is (= 41 ((ns-resolve test-ns 'read-value)))))

        (binding [*ns* test-ns]
          (let [migration-error
                (try
                  (eval '(az/defn migrate-struct-state :- :void
                           [old-address :- :usize new-address :- :usize]
                           (ak/const old-state [:*const OldCounterState]
                             (ak/ptrFromInt old-address))
                           (ak/const new-state [:* CounterState]
                             (ak/ptrFromInt new-address))
                           (az/assign "="
                             (az/field (az/deref new-state) value)
                             (az/field (az/deref old-state) value))
                           (az/assign "="
                             (az/field (az/deref new-state) extra)
                             99)))
                  nil
                  (catch clojure.lang.ExceptionInfo error error))]
            (is (= :zig-state-migration-required
                   (:aguafria/phase (ex-data migration-error))))))

        (az/migrate-state!
         (symbol (str test-symbol) "state")
         (symbol (str test-symbol) "migrate-struct-state"))
        (binding [*ns* test-ns]
          (eval '(az/defn read-extra :- :i32 []
                   (az/field state extra))))
        (is (= 41 ((ns-resolve test-ns 'read-value))))
        (is (= 99 ((ns-resolve test-ns 'read-extra))))
        (let [versions (az/state-versions
                        (symbol (str test-symbol) "state"))]
          (is (= [:retained :migrated] (mapv :status versions)))
          (is (not= (:schema-fingerprint (first versions))
                    (:schema-fingerprint (second versions)))))
        (finally
          (az/configure! old-config)
          (remove-ns test-symbol))))))

(deftest cross-namespace-defvar-capsule-test
  (testing "old and new callers in different namespaces share one stable native state capsule"
    (let [old-config (az/configuration)
          suffix fixture-suffix
          a-symbol (symbol (str "aguafria.state-a-" suffix))
          b-symbol (symbol (str "aguafria.state-b-" suffix))
          a-ns (create-ns a-symbol)
          b-ns (create-ns b-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (doseq [target [a-ns b-ns]]
          (binding [*ns* target]
            (refer 'clojure.core)
            (alias 'az 'aguafria.zig)))
        (binding [*ns* a-ns]
          (eval '(az/defvar counter :i32 1))
          (eval '(az/defn read-a :- :i32 [] counter))
          (eval '(az/defn write-a :- :void
                   [value :- :i32]
                   (az/assign "=" counter value))))
        (binding [*ns* b-ns]
          (alias 'a a-symbol)
          (eval '(az/defn read-b :- :i32 [] a/counter))
          (eval '(az/defn write-b :- :void
                   [value :- :i32]
                   (az/assign "=" a/counter value))))

        ((ns-resolve b-ns 'write-b) 9)
        (is (= 9 ((ns-resolve a-ns 'read-a))))
        (is (= 9 ((ns-resolve b-ns 'read-b))))

        ;; Reloading only A keeps B's already-compiled accessor on the same
        ;; canonical address; no dependent recompilation is required.
        (binding [*ns* a-ns]
          (eval '(az/defn read-a :- :i32 [] (+ counter 1))))
        (is (= 10 ((ns-resolve a-ns 'read-a))))
        (is (= 9 ((ns-resolve b-ns 'read-b))))

        (binding [*ns* b-ns]
          (eval '(az/defn read-b :- :i32 [] (+ a/counter 2))))
        (is (= 11 ((ns-resolve b-ns 'read-b))))
        ((ns-resolve a-ns 'write-a) 20)
        (is (= 22 ((ns-resolve b-ns 'read-b))))
        (is (= 21 ((ns-resolve a-ns 'read-a))))
        (finally
          (az/configure! old-config)
          (remove-ns b-symbol)
          (remove-ns a-symbol))))))

(deftest standalone-build-and-stats-test
  (let [artifact (az/build! 'aguafria.zig-integration-test
                            {:kind :exe
                             :name "aguafria-integration"
                             :optimize "ReleaseFast"})
        execution (shell/sh (:output-path artifact))
        module-stats (az/stats 'aguafria.zig-integration-test)
        all-stats (az/stats)]
    (testing "the same generated source builds as an optimized pure Zig program"
      (is (= :exe (:kind artifact)))
      (is (= "ReleaseFast" (:optimize artifact)))
      (is (.isFile (java.io.File. (:output-path artifact))))
      (is (some #{"-OReleaseFast"} (:command artifact)))
      (is (not (str/includes? (slurp (:source-path artifact))
                              "__aguafria_")))
      (is (zero? (:exit execution))))
    (testing "monitor-friendly statistics are plain inspectable data"
      (is (= :finished (:status module-stats)))
      (is (pos? (:declaration-count module-stats)))
      (is (some #(and (= "base" (:name %))
                      (= 64 (count (:abi-fingerprint %))))
                (:declarations module-stats)))
      (is (some #(and (= "Point" (:name %))
                      (= 64 (count (:schema-fingerprint %))))
                (:declarations module-stats)))
      (is (some #(and (= "main" (:name %)) (= :finished (:state %)))
                (:declarations module-stats)))
      (is (some #(= :standalone-program (:purpose %)) (:builds module-stats)))
      (is (pos? (:native-generation-count module-stats)))
      (is (pos? (:dispatch-version-count module-stats)))
      (is (every? integer?
                  (keep :implementation-generation
                        (:dispatch-versions module-stats))))
      (is (zero? (get-in all-stats [:summary :active-build-count])))
      (is (pos? (get-in all-stats [:summary :finished-build-count])))
      (is (pos? (get-in module-stats
                        [:timings :native-build-ms :count])))
      (is (number? (get-in module-stats
                           [:timings :end-to-end-ms :p95-ms])))
      (is (pos? (get-in all-stats
                        [:timings :by-purpose :repl-shared-library
                         :native-build-ms :count])))
      (is (nat-int? (get-in module-stats
                            [:timings :cache :hit-count]))))))

(deftest source-only-transitive-standalone-build-test
  (testing "a build collects declarations without dev dylibs and links the full static graph"
    (let [old-config (az/configuration)
          suffix fixture-suffix
          leaf-symbol (symbol (str "aguafria.static-leaf-" suffix))
          middle-symbol (symbol (str "aguafria.static-middle-" suffix))
          root-symbol (symbol (str "aguafria.static-root-" suffix))
          leaf-ns (create-ns leaf-symbol)
          middle-ns (create-ns middle-symbol)
          root-ns (create-ns root-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (doseq [target [leaf-ns middle-ns root-ns]]
          (binding [*ns* target]
            (refer 'clojure.core)
            (alias 'az 'aguafria.zig)))
        (binding [runtime/*source-only-registration?* true]
          (binding [*ns* leaf-ns]
            (eval '(az/defn answer :- :u32 [] 42)))
          (binding [*ns* middle-ns]
            (alias 'leaf leaf-symbol)
            (eval '(az/defn forwarded :- :u32 [] (leaf/answer))))
          (binding [*ns* root-ns]
            (alias 'middle middle-symbol)
            (eval '(az/defn main {:attrs #{:public}} :- :void []
                     (set! _ (middle/forwarded))))))
        (is (every? :source-only?
                    (map #(runtime/module-info %)
                         [leaf-symbol middle-symbol root-symbol])))
        (is (empty?
             (mapcat #(get-in (az/stats %) [:native-generations])
                     [leaf-symbol middle-symbol root-symbol])))
        (let [artifact (az/build! root-symbol
                                  {:kind :exe
                                   :name (str "aguafria-static-" suffix)
                                   :optimize "ReleaseFast"})
              execution (shell/sh (:output-path artifact))]
          (is (zero? (:exit execution)))
          (is (some #(str/starts-with? % (str "-M" middle-symbol "="))
                    (:command artifact)))
          (is (some #(str/starts-with? % (str "-M" leaf-symbol "="))
                    (:command artifact)))
          (is (not (str/includes? (slurp (:source-path artifact))
                                  "__aguafria_"))))
        (finally
          (az/configure! old-config)
          (remove-ns root-symbol)
          (remove-ns middle-symbol)
          (remove-ns leaf-symbol))))))

(deftest scalar-boundary-test
  (binding [*ns* (the-ns 'aguafria.zig-integration-test)]
    (eval
     '(az/defn truthy :- :bool
        [x :- :i32]
        (> x 0))))
  (let [test-ns (the-ns 'aguafria.zig-integration-test)]
    (is (true? ((ns-resolve test-ns 'truthy) 1)))
    (is (false? ((ns-resolve test-ns 'truthy) 0)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Wrong number of arguments"
                          ((ns-resolve test-ns 'truthy) 1 2)))))

(deftest rust-style-compiler-diagnostic-test
  (let [old-config (az/configuration)]
    (try
      (az/configure! {:async? false :modules {}})
      (let [error (try
                    (runtime/register-declaration!
                     {:kind :const
                      :name 'broken-constant
                      :declaration-key [:const 'broken-constant]
                      :module "aguafria.error-fixture"
                      :type :i32
                      :value 'not-defined-in-zig
                      :public? true
                      :source {:file "test/aguafria/zig_integration_test.clj"
                               :line (:line (meta #'rust-style-compiler-diagnostic-test))
                               :column 1}})
                    nil
                    (catch clojure.lang.ExceptionInfo error error))]
        (is (some? error))
        (is (= :zig-compile (:aguafria/phase (ex-data error))))
        (is (seq (:diagnostics (ex-data error))))
        (is (str/includes? (ex-message error) "error[aguafria::zig]"))
        (is (str/includes? (ex-message error) "test/aguafria/zig_integration_test.clj"))
        (is (str/includes? (ex-message error) "this Clojure form generated the failing Zig"))
        (is (str/includes? (ex-message error) "Zig reported the error here"))
        (is (str/includes? (ex-message error) "compiler command")))
      (finally
        (az/configure! old-config)))))
