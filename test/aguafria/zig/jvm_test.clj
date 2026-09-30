(ns aguafria.zig.jvm-test
  (:require [aguafria.keyword :as ak]
            [aguafria.std :as std]
            [aguafria.std.ArrayList :as array-list]
            [aguafria.std.ArrayList.Slice :as array-list-slice]
            [aguafria.std.SemanticVersion :as semantic-version]
            [aguafria.std.testing :as zig-testing]
            [aguafria.std.debug :as debug]
            [aguafria.std.c :as c]
            [aguafria.std.math :as math]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as az]
            [clojure.edn :as edn]
            [aguafria.zig.jvm :as native-call]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io StringWriter]))

(defn- values=
  "Compare explicit JVM snapshots; native identity is asserted separately."
  [& values]
  (apply = (map #(walk/postwalk (fn [item]
                                  (if (value/zig-value? item) (az/value item) item))
                                %)
                values)))

(defn- fixture []
  (let [namespace (create-ns (gensym "aguafria.native-call-fixture-"))]
    (binding [*ns* namespace]
      (refer 'clojure.core)
      (require '[aguafria.zig :as az] '[aguafria.keyword :as ak]
               '[aguafria.std.debug :as debug]))
    namespace))

(defn- without-compilation [f]
  (let [commands (atom [])
        original shell/sh
        result (with-redefs [shell/sh (fn [& arguments]
                                        (swap! commands conj (take 2 arguments))
                                        (apply original arguments))]
                 (f))]
    (is (empty? @commands) (str "Warm handler launched processes: " @commands))
    result))

(deftest tuple-results-preserve-native-element-types
  (let [result (ak/++ [(ak/u32 1234) (ak/f64 12.34)]
                      [[(ak/i16 7)]])
        [integer floating nested] result]
    (is (= :u32 (value/qualified-type integer)))
    (is (= :f64 (value/qualified-type floating)))
    (is (= :i16 (value/qualified-type (first nested))))
    (is (values= [1234 12.34 [7]] result))
    (is (= [:*const :u32] (value/qualified-type (ak/& integer))))
    (is (values= 1234 @(ak/& integer)))))

(deftest runtime-tuples-preserve-native-storage
  (let [result (ak/mulWithOverflow (ak/u64 12) (ak/u64 10))
        mutable (ak/var result)]
    (is (value/zig-value? result))
    (is (= '(aguafria.keyword/Tuple (aguafria.keyword/& [:u64 :u1]))
           (value/qualified-type result)))
    (is (= [120 0] (az/value result)))
    (ak/= mutable (ak/addWithOverflow (az/get mutable 0) (ak/u64 3)))
    (is (= [123 0] (az/value mutable)))
    (is (= [120 0] (az/value result)))
    (is (= [123 0] (az/value @(ak/& mutable))))
    (let [[number overflow] mutable]
      (is (= :u64 (value/qualified-type number)))
      (is (= :u1 (value/qualified-type overflow)))
      (ak/= number (ak/u64 124))
      (is (= [124 0] (az/value mutable))))
    (is (= :missing (nth mutable 2 :missing)))
    (is (= :missing (nth mutable -1 :missing)))
    (is (thrown? IndexOutOfBoundsException (nth mutable 2)))))

(deftest comptime-aggregates-retain-compiler-expressions
  (let [integer (ak/typeInfo :u8)
        array (ak/typeInfo (az/type [:array 4 :u16]))
        error-union (ak/typeInfo (az/type [:error-union :anyerror :i32]))]
    (is (= :comptime-expression (:representation (value/realize! integer))))
    (is (= {:int {:signedness :unsigned :bits 8}} (az/value integer)))
    (is (values= 8 (:bits (:int integer))))
    (is (= :u16 (:type (value/type-info (:child (:array array))))))
    (is (values= 4 (:len (:array array))))
    (is (= :i32 (:type (value/type-info (:payload (:error_union error-union))))))
    (is (true? (:is_const (:pointer (ak/typeInfo [:*const :u8])))))
    (is (nil? (:segment (value/realize! integer))))
    (is (some? (ak/TypeOf integer)))))

(deftest comptime-aggregate-function-results-round-trip
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.precompile-comptime-fixture :reload))
  (let [metadata ((resolve 'aguafria.zig.precompile-comptime-fixture/metadata) :u8)
        callable ((resolve 'aguafria.zig.precompile-comptime-fixture/callable) :u8)]
    (is (= {:element {:type "u8"} :count 3} (az/value metadata)))
    (is (= :u8 (:type (value/type-info (:element metadata)))))
    (is (values= 3 (:count metadata)))
    (is (false? (:is_tuple (:struct (ak/typeInfo
                                     @(resolve 'aguafria.zig.precompile-comptime-fixture/Metadata))))))
    (is (re-matches #"fn \(i32\).*i32"
                    (get-in (az/value callable) [:function :function-type])))
    (is (values= 42 ((:function callable) 41)))))

(deftest runtime-handler-reuse-is-independent-of-operand-values
  (ak/+ 1 2)
  (is (values= 300 (without-compilation #(ak/+ 100 200))))
  (ak// 7.0 3.0)
  (is (values= 2.5 (without-compilation #(ak// 10.0 4.0))))
  (with-open [number (ak/var 1 :u32)
              array (ak/var (az/array [10 20 30 40 50] :i32))
              start (ak/var 0 :usize)]
    (ak/+= number 1)
    (without-compilation #(ak/+= number 3))
    (is (values= 5 number))
    (az/get array 0)
    (is (values= 30 (without-compilation #(az/get array 2))))
    (az/slice array start 3)
    (is (values= [10 20 30 40] (without-compilation #(az/slice array start 4))))))

(deftest array-length-changes-reuse-runtime-slice-handlers
  (letfn [(exercise [array start]
            (let [slice (az/slice array start 4)]
              (is (= [:slice :u8] (value/qualified-type slice)))
              (is (values= {:ok nil} (zig-testing/expectEqual 2 (:len slice))))
              (is (values= {:ok nil} (zig-testing/expectEqual 4 (az/get array 3))))
              (ak/+= (az/get slice 1) 1)
              (is (values= {:ok nil} (zig-testing/expectEqual 5 (az/get array 3))))))]
    (with-open [start (ak/var 2 :usize)]
      (doseq [length [10 11 12]]
        ;; Construction retains the exact [N]u8 type. Only the subsequent
        ;; runtime operations must be independent of N.
        (with-open [array (ak/var (az/array (vec (range 1 (inc length))) :u8))]
          (is (= [:array length :u8] (value/qualified-type array)))
          (if (= length 10)
            (exercise array start)
            (without-compilation #(exercise array start))))))))

(deftest values-lesson-reuses-handlers-with-changed-runtime-values
  (let [errors (az/type [:error-set [:ExampleErrorVariant]])]
    (letfn [(exercise [a b numerator denominator flag text number]
              (let [output (StringWriter.)]
                (binding [*out* output *err* output]
                  (debug/print "1 + 1 = {}\n" [(ak/i32 (ak/+ a b))])
                  (debug/print "7.0 / 3.0 = {}\n" [(ak/f32 (ak// numerator denominator))])
                  (debug/print "{}\n{}\n{}\n" [(and true false) (or true false) (ak/! flag)])
                  (with-open [optional-value (ak/var (ak/as nil [:optional [:slice-const :u8]]))]
                    (debug/assert (ak/== optional-value nil))
                    (debug/print "\noptional 1\ntype: {}\nvalue: {?s}\n"
                                 [(ak/TypeOf optional-value) optional-value])
                    (ak/= optional-value text)
                    (debug/assert (ak/!= optional-value nil))
                    (debug/print "\noptional 2\ntype: {}\nvalue: {?s}\n"
                                 [(ak/TypeOf optional-value) optional-value]))
                  (with-open [number-or-error (-> (:ExampleErrorVariant errors)
                                                  (ak/as [:error-union errors :i32]) ak/var)]
                    (debug/print "\nerror union 1\ntype: {}\nvalue: {!}\n"
                                 [(ak/TypeOf number-or-error) number-or-error])
                    (ak/= number-or-error number)
                    (debug/print "\nerror union 2\ntype: {}\nvalue: {!}\n"
                                 [(ak/TypeOf number-or-error) number-or-error])))
                (str output)))]
      (is (str/includes? (exercise 1 1 7.0 3.0 true "hi" 1234) "value: 1234"))
      (let [output (without-compilation #(exercise 2 3 9.0 4.0 false "hello again" 5678))]
        (doseq [expected ["1 + 1 = 5" "7.0 / 3.0 = 2.25" "false\ntrue\ntrue"
                          "value: null" "value: hello again" "value: error.ExampleErrorVariant"
                          "value: 5678"]]
          (is (str/includes? output expected)))))))

(deftest integer-operator-families-reuse-native-handlers
  (doseq [operation [ak/+% ak/-% ak/*% ak/+| ak/-| ak/*|]]
    (operation 20 10)
    (without-compilation #(operation 30 12)))
  (is (values= -16 (ak/+% (ak/i8 120) (ak/i8 120))))
  (is (values= 127 (ak/+| (ak/i8 120) (ak/i8 120)))))

(deftest reusable-array-views-preserve-bounds-and-constness
  (with-open [array (az/array [1 2 3 4] :u8)
              start (ak/var 1 :usize)]
    (let [slice (az/slice array start 3)]
      (is (= [:slice-const :u8] (value/qualified-type slice)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mutable native value"
                            (ak/+= (az/get slice 0) 1))))
    (is (thrown? clojure.lang.ExceptionInfo (az/get array 4)))
    (is (thrown? clojure.lang.ExceptionInfo (az/slice array start 5)))
    (ak/= start 3)
    (is (thrown? clojure.lang.ExceptionInfo (az/slice array start 2)))
    ;; Constant bounds must still produce a pointer to a fixed-size array.
    (is (values= {:ok nil}
                 (zig-testing/expectEqual (az/type [:*const [:array 2 :u8]])
                                          (ak/TypeOf (az/slice array 1 3))))))
  (with-open [array (az/array [false true] :bool)]
    (is (false? (az/get array 0)))
    (is (true? (az/get array 1))))
  (with-open [array (ak/var (az/array [] :u8))
              start (ak/var 0 :usize)]
    (is (values= 0 (:len (az/slice array start 0)))))
  (with-open [array (ak/var (az/array [1 2] :u8))]
    (with-open [pointer (value/array-elements-pointer array)]
      (is (= [:many :u8] (value/qualified-type pointer))))
    ;; Closing the temporary pointer handle must not close its array owner.
    (is (values= 2 (az/get array 1)))))

(deftest dereferenced-boolean-pointers-remain-assignable
  (with-open [flag (ak/var false :bool)]
    (let [pointer (ak/& flag)
          view @pointer]
      (is (false? (az/value view)))
      (ak/= view true)
      (is (true? (az/value flag))))))

(deftest reusable-handlers-preserve-extended-floats-and-pointer-offsets
  (doseq [type [:f16 :f80 :f128]]
    (with-open [number (ak/as 1.5 type)]
      (is (values= 1.5 @(ak/& number)))
      (ak/+ number 2.0)
      (is (values= 5.5 (without-compilation #(ak/+ number 4.0))))))
  (with-open [array (az/array [10 20 30] :i32)
              pointer (ak/as (ak/& array) [:many-const :i32])]
    (ak/+ pointer 1)
    (let [advanced (without-compilation #(ak/+ pointer 2))]
      (is (values= 30 (az/get advanced 0))))))

(deftest vector-constructor-executes-on-the-jvm
  (with-open [a (az/vector [1 2 3 4] :i32)
              b (az/vector [5 6 7 8] :i32)
              odd (az/vector [1 2 3] :i32)
              narrow (az/vector [1 2 3 4] :u3)
              floats (az/vector [1.0 2.0 3.0] :f32)
              chars (az/vector [\o \l \h \e \r \z \w] :u8)
              flags (az/vector [true false] :bool)]
    (is (values= "@Vector(4, i32)" (:type (az/describe a))))
    (is (values= [1 2 3 4] (az/value a)))
    (is (values= [6 8 10 12] (az/value (ak/+ a b))))
    (is (values= [1 2 3] (az/value odd)))
    (is (values= [1 2 3 4] (az/value narrow)))
    (is (values= [2 4 6 0] (az/value (ak/+% narrow narrow))))
    (is (values= [1.0 2.0 3.0] (az/value floats)))
    (is (values= [111 108 104 101 114 122 119] (az/value chars)))
    (is (values= [2.0 4.0 6.0] (az/value (ak/+ floats floats))))
    (is (values= [false true] (az/value (ak/! flags))))
    (is (values= [true false] (az/value flags)))))

(deftest pointers-borrow-stable-storage-and-preserve-constness
  (let [x (ak/i32 1234)
        pointer (ak/& x)]
    (is (values= {:ok nil} (zig-testing/expectEqual 1234 @pointer)))
    (is (values= {:ok nil} (zig-testing/expectEqual (az/type [:*const :i32])
                                                    (ak/TypeOf pointer))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mutable native value"
                          (ak/+= @pointer 1))))
  (with-open [y (ak/var 5678 :i32)
              pointer (ak/& y)]
    (is (values= {:ok nil} (zig-testing/expectEqual (az/type [:* :i32]) (ak/TypeOf pointer))))
    (ak/+= @pointer 1)
    (is (values= 5679 (az/value y)))
    (is (values= {:ok nil} (zig-testing/expectEqual 5679 @pointer))))
  (is (values= 1 (az/value (ak/& 3 1))) "binary & remains bitwise AND"))

(deftest indexed-values-borrow-the-original-storage
  (with-open [array (ak/var (az/array [1 2 3 4 5 6 7 8 9 10] :u8))]
    (let [element (az/get array 2)
          ptr (ak/& element)]
      (is (values= {:ok nil} (zig-testing/expectEqual (az/type [:* :u8]) (ak/TypeOf ptr))))
      (is (values= {:ok nil} (zig-testing/expectEqual 3 (az/get array 2))))
      (is (= (+ 2 (.address (value/segment array)))
             (.address (value/segment element))))
      (ak/+= @ptr 1)
      (is (values= {:ok nil} (zig-testing/expectEqual 4 (az/get array 2))))
      (ak/= element 12)
      (is (values= [1 2 12 4 5 6 7 8 9 10] (az/value array)))))
  (with-open [array (az/array [1 2 3] :u8)]
    (let [element (az/get array 2)
          ptr (ak/& element)]
      (is (values= {:ok nil} (zig-testing/expectEqual (az/type [:*const :u8]) (ak/TypeOf ptr))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mutable native value"
                            (ak/+= @ptr 1)))
      (is (values= [1 2 3] (az/value array)))))
  (with-open [grid (ak/var (az/array [[1 2] [3 4]] [:array 2 :i32]))]
    (ak/+= (az/get-in grid [1 0]) 10)
    (is (values= [[1 2] [13 4]] (az/value grid)))))

(deftest indexed-views-preserve-zig-layout-and-pointee-constness
  ;; u24 occupies four bytes; a sentinel is stored after the logical length.
  (with-open [array (ak/var (az/array [10 20] {:sentinel 0} :u24))]
    (ak/+= (az/get array 1) 3)
    (is (values= [10 23] (az/value array)))
    (is (values= 0 (az/get array 2))))
  (with-open [array (ak/var (az/array [1 2 3] :i32))]
    ;; The slice binding is constant, but its pointees are mutable.
    (let [slice (ak/as (ak/& array) [:slice :i32])]
      (ak/+= (az/get slice 1) 10)
      (is (values= [1 12 3] (az/value array))))
    ;; A mutable slice binding does not make its const pointees mutable.
    (let [slice (ak/var (ak/as (ak/& array) [:slice-const :i32]))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mutable native value"
                            (ak/+= (az/get slice 1) 10)))))
  (with-open [lanes (az/vector [1 2 3 4] :i32)]
    (is (values= 3 (az/get lanes 2)))))

(deftest compound-assignment-preserves-independent-operand-types
  (with-open [array (az/array [1 2 3 4] :i32)
              pointer (ak/var (ak/& array) [:many-const :i32])]
    (ak/+= pointer 1)
    (is (values= 2 (az/get pointer 0)))
    (ak/+= pointer (ak/as 1 :usize))
    (is (values= 3 (az/get pointer 0)))
    (ak/-= pointer 2)
    (is (values= 1 (az/get pointer 0))))
  (with-open [number (ak/var 3 :u8)]
    (ak/<<= number (ak/as 2 :u3))
    (is (values= 12 number))
    (ak/>>= number 1)
    (is (values= 6 number))
    (ak/+= number 1)
    (is (values= 7 number))))

(deftest field-views-borrow-original-header-storage
  (with-open [array (ak/var (az/array [1 2 3 4] :i32))
              slice (ak/var (ak/as (ak/& array) [:slice :i32]))]
    (is (values= {:ok nil} (zig-testing/expectEqual 4 (:len slice))))
    (ak/+= (:ptr slice) 1)
    (ak/-= (:len slice) 1)
    (is (values= [2 3 4] (az/value slice)))
    (is (values= {:ok nil} (zig-testing/expectEqual 3 (:len slice))))
    (is (false? (ak/== -1 (:len slice))))
    (ak/= (az/get slice 0) 20)
    (is (values= [1 20 3 4] (az/value array))))
  (with-open [array (az/array [1 2] :i32)
              slice (ak/as (ak/& array) [:slice-const :i32])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mutable native value"
                          (ak/+= (:ptr slice) 1))))
  (let [namespace (fixture)]
    (binding [*ns* namespace]
      (eval '(az/defstruct Pair [[:x :i32] [:y :i32]]))
      (with-open [pair (eval '(ak/var (Pair {:x 1 :y 2})))]
        (ak/+= (:x pair) 5)
        (is (values= {:x 6 :y 2} (az/value pair)))))))

(deftest literal-arithmetic-retains-comptime-types
  (with-open [result (ak/i32 (ak/+ 1 1))]
    (is (values= 2 result)))
  (let [sum (ak/+ 1 1)
        quotient (ak// 7.0 3.0)]
    (is (value/zig-value? sum))
    (is (= :comptime_int (value/qualified-type sum)))
    (is (= :comptime_float (value/qualified-type quotient)))
    (with-open [integer (ak/i32 sum)
                floating (ak/f32 quotient)]
      (is (values= 2 integer))
      (is (= (float (/ 7.0 3.0)) (float (az/value floating))))))
  ;; Retaining literal context must not silently narrow explicitly typed values.
  (with-open [typed (ak/i64 2)]
    (is (thrown? clojure.lang.Compiler$CompilerException (ak/i32 typed)))))

(deftest literal-arithmetic-composes-with-native-operands
  (doseq [[result expected] [[(ak/- 5 8) -3]
                             [(ak/* 7 6) 42]
                             [(ak/+ (bigint "18446744073709551615") 1)
                              (bigint "18446744073709551616")]]]
    (is (= :comptime_int (value/qualified-type result)))
    (is (values= expected result)))
  (with-open [unsigned (ak/u64 2)
              sum (ak/+ unsigned 1)]
    (is (= :u64 (value/qualified-type sum)))
    (is (values= 3 sum))
    (is (true? (ak/== sum 3))))
  (is (false? (ak/< 3 2)))
  (is (true? (ak/== (ak/+ 1 1) 2))))

(deftest numeric-results-retain-native-identity
  (doseq [type [:i8 :u8 :i32 :u32 :i64 :u64 :f32 :f64 :c_int :c_uint]]
    (let [number (ak/as 12 type)
          sum (ak/+ number number)
          pointer (ak/& sum)]
      (is (value/zig-value? number))
      (is (value/zig-value? sum))
      (is (values= 24.0 (double (az/value sum))))
      (is (values= {:ok nil} (zig-testing/expectEqual (az/type type) (ak/TypeOf sum))))
      (is (values= {:ok nil} (zig-testing/expectEqual sum @pointer)))))
  (is (false? (ak/== (ak/i32 1) (ak/i32 2))))
  (let [namespace (fixture)]
    (binding [*ns* namespace]
      (eval '(az/defn twelve :i32 [] 12))
      (let [result (eval '(twelve))]
        (is (value/zig-value? result))
        (is (values= 12 (az/value result)))))))

(deftest vector-array-slice-conversion-body-runs-on-the-jvm
  (let [arr1 (az/array [1.1 3.2 4.5 5.6] :f32)
        vec (ak/as arr1 (ak/Vector 4 :f32))
        arr2 (ak/as vec [:array 4 :f32])]
    (is (values= {:ok nil} (zig-testing/expectEqual arr1 arr2)))
    (let [vec2 (ak/as @(az/slice arr1 1 3) (ak/Vector 2 :f32))
          slice (ak/as (ak/& arr1) [:slice-const :f32])
          offset (ak/var 1 :u32)]
      (ak/= :_ (ak/& offset))
      (let [vec3 (-> (az/slice slice offset)
                     (az/slice 0 2)
                     deref
                     (ak/as (ak/Vector 2 :f32)))]
        (is (values= {:ok nil} (zig-testing/expectEqual (az/get slice offset) (az/get vec2 0))))
        (is (values= {:ok nil} (zig-testing/expectEqual (az/get slice (ak/+ offset 1)) (az/get vec2 1))))
        (is (values= {:ok nil} (zig-testing/expectEqual vec2 vec3)))))))

(deftest debug-reports-native-types-without-running-the-probe
  (let [namespace (fixture)
        source-file (str (ns-name namespace) ".clj")
        configuration (merge {:debug-output #{:print :file}
                              :debug-report-file ".aguafria/debug/types.edn"}
                             (az/configuration))
        report-file (str (java.nio.file.Files/createTempDirectory
                          "aguafria-debug-test-"
                          (make-array java.nio.file.attribute.FileAttribute 0))
                         "/types.edn")]
    (try
      (az/configure! {:debug-output #{:file} :debug-report-file report-file})
      (binding [*ns* namespace *file* source-file]
        (eval '(az/defvar calls :i32 0))
        (eval '(az/defn increment :i32 [] (ak/+= calls 1) calls))
        (let [output (with-out-str
                       (eval (with-meta '(az/defn inspect-value :i32 []
                                           (az/debug! (increment)))
                               {:file source-file :line 20 :column 1})))]
          (is (values= "" output)))
        (is (values= 0 (az/value (var-get (ns-resolve namespace 'calls)))))
        (is (values= 1 (eval '(inspect-value))))
        (is (values= 2 (eval '(inspect-value))))
        (eval '(az/defn inspect-vector [:vector 4 :i32] []
                 (-> (az/vector [1 2 3 4] :i32) az/debug!)))
        (is (values= [1 2 3 4] (az/value (eval '(inspect-vector)))))
        (is (var? (eval '(az/debug! (az/defn top-level :i32 [] 7)))))
        (is (values= 7 (eval '(top-level)))))
      (let [reports (:reports (edn/read-string (slurp report-file)))
            report (first (filter #(and (= source-file (:file %))
                                        (= "(increment)" (:form %))) reports))]
        (is (values= :compile (:phase report)))
        (is (values= "i32" (:type report)))
        (is (pos? (:line report)))
        (is (pos? (:column report)))
        (is (some #(= "@Vector(4, i32)" (:type %)) reports)))
      (let [calls (atom 0)
            value (az/vector [3 6] :i32)]
        (is (identical? value (az/debug! (do (swap! calls inc) value))))
        (is (values= 1 @calls)))
      (finally (az/configure! configuration)))))

(deftest syntax-vars-retain-identity-and-metadata-on-reload
  (let [before (ns-publics 'aguafria.zig)]
    (require 'aguafria.zig :reload)
    (is (values= (set (keys before)) (set (keys (ns-publics 'aguafria.zig)))))
    (doseq [[name v] before]
      (is (identical? v (ns-resolve 'aguafria.zig name)) (str name))))
  (doseq [v [#'az/array #'az/range #'az/with-block #'az/type]]
    (is (values= (:name (meta v)) (get-in (meta v) [:aguafria/syntax :name])))
    (is (seq (:doc (meta v))))
    (is (seq (:arglists (meta v)))))
  (is (true? (:macro (meta #'az/with-block))))
  (is (values= '([elements element-type] [elements options element-type])
               (:arglists (meta #'az/array))))
  (is (values= '([start] [start end]) (:arglists (meta #'az/range))))
  (is (values= '([label & body]) (:arglists (meta #'az/with-block)))))

(deftest nested-access-works-on-native-values-from-the-jvm
  (let [Point (az/struct [[:x :i32] [:y :i32]])]
    (with-open [points (az/array [{:x 4 :y 8} {:x 7 :y 14}] Point)
                grid (az/array [[1 2] [3 4]] [:array 2 :i32])]
      (is (values= 4 (az/get-in points [0 :x])))
      (is (values= 8 (az/get-in points [0 :y])))
      (is (values= 2 (az/get points :len)))
      (is (values= 8 (az/get (az/get points 0) :y)))
      (let [index 1]
        (is (values= 14 (az/get-in points [index :y]))))
      (is (values= 3 (az/get-in grid [1 0])))
      (is (identical? points (az/get-in points [])))
      (let [calls (atom 0)]
        (is (values= 7 (az/get-in (do (swap! calls inc) points) [1 :x])))
        (is (values= 1 @calls))))))

(deftest keywords-access-native-fields-from-clojure-and-zig
  (let [namespace (fixture)]
    (binding [*ns* namespace]
      (eval '(az/defstruct Point [[:x :i32] [:y :i32]]))
      (eval '(az/defn make-point Point
               [[x :i32]]
               (Point {:x x :y (ak/* x 2)})))
      (eval '(az/defn point-x :i32
               [[x :i32]]
               (:x (make-point x))))
      (is (values= 3 (eval '(:x (make-point 3)))))
      (is (values= 3 (eval '(point-x 3))))
      (is (values= 6 (eval '(-> (make-point 3) :y))))))
  (let [Record (az/struct [[:active :bool] [:optional [:optional :i32]]])
        Container (az/struct [[:answer {:const 42} :i32]])]
    (with-open [record (Record {:active false :optional nil})
                items (az/array [1 2 3] :i32)]
      (is (false? (:active record)))
      (is (nil? (:optional record)))
      (is (values= 3 (:len items)))
      (is (values= 42 (:answer Container)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"default value"
                            (:active record true))))))

(deftest keyword-labeled-blocks-execute-on-the-jvm
  (let [answer 42]
    (is (values= answer (az/with-block :result (ak/break :result answer)))))
  (is (values= 7 (az/with-block :outer
                   (let [inner (az/with-block :inner
                                 (ak/break :inner 7))]
                     (ak/break :outer inner)))))
  (with-open [counter (ak/var 1 :i32)]
    (is (values= 2 (az/with-block :updated
                     (ak/+= counter 1)
                     (ak/break :updated counter))))
    (is (values= 2 (az/value counter))))
  (is (values= [0 1 2] (az/with-block :init
                         (let [items (ak/var ak/undefined [:array 3 :u32])]
                           (ak/for [(ak/* item) (ak/& items) i (az/range 0)]
                             (ak/= @item (ak/intCast i)))
                           (ak/break :init items)))))
  (is (nil? (ns-resolve 'aguafria.zig 'labeled-block))))

(deftest arrays-and-operators-execute-on-the-jvm
  (with-open [left (az/array [1 2 3 4] :i32)
              right (az/array [5 6 7 8] :i32)
              nested (az/array [[1 2] [3 4]] [:array 2 :u8])
              empty-array (az/array [] :u8)]
    (is (values= 1 (az/index left 0)))
    (is (values= 4 (az/field left :len)))
    (is (values= [1 2 3 4 5 6 7 8] (ak/++ left right)))
    (is (values= [1 2 3 4 1 2 3 4] (ak/** left 2)))
    (is (values= [] (ak/** left 0)))
    (is (values= [[1 2] [3 4]] (az/value nested)))
    (is (values= [] (az/value empty-array))))
  ;; Zig string operations return pointers to sentinel arrays, not array copies.
  (is (values= (mapv int "hello world") @(ak/++ "hello" " " "world")))
  (is (values= (mapv int "ababab") @(ak/** "ab" 3)))
  (with-open [amount (ak/var 2 :u6)
              dividend (ak/var 10 :u64)
              divisor (ak/var 3 :u64)]
    (is (values= 8 (ak/<< (ak/i64 2) amount)))
    (is (values= 1 (ak/% dividend divisor))))
  (is (thrown? clojure.lang.ArityException (az/array [1 2])))
  (is (nil? (ns-resolve 'aguafria.zig 'array-init))))

(deftest sentinel-arrays-execute-on-the-jvm
  (with-open [array (az/array [1 25 3 4] {:sentinel 0} :u8)
              embedded (az/array [1 0 0 4] {:sentinel 0} :u8)
              empty-array (az/array [] {:sentinel 255} :u8)
              flags (az/array [true false] {:sentinel false} :bool)]
    (is (values= [1 25 3 4] (az/value array)))
    (is (values= 4 (:len array)))
    (is (values= 0 (az/get array 4)))
    (is (ak/== (az/type [:array 4 {:sentinel 0} :u8]) (ak/TypeOf array)))
    (is (values= [1 0 0 4] (az/value embedded)))
    (is (values= 4 (:len embedded)))
    (is (values= 0 (az/get embedded 4)))
    (is (values= 0 (:len empty-array)))
    (is (values= 255 (az/get empty-array 0)))
    (is (false? (az/get flags 2))))
  (doseq [options [{:sentinal 0} :sentinel {:sentinel 0 :length 1}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"array options"
                          (az/array [1] options :u8)))))

(deftest sentinel-array-schemas-execute-on-the-jvm
  (with-open [array (az/init [1 2] [:array 2 {:sentinel 9} :u8])
              inferred (az/init [3 4] [:array :_ {:sentinel 0} :u8])
              nested (az/array [[1 2] [3 4]] [:array 2 {:sentinel 255} :u8])]
    (is (values= [1 2] (az/value array)))
    (is (values= 9 (az/get array 2)))
    (is (values= 0 (az/get inferred 2)))
    (is (values= [[1 2] [3 4]] (az/value nested)))
    (is (values= 255 (az/with-block :result
                       (ak/break :result (az/get-in nested [1 2]))))))
  (is (thrown? Exception
               (az/init [1 2] [:array-sentinel 2 0 :u8]))))

(deftest flat-pointer-captures-execute-on-the-jvm
  (with-open [items (ak/var (az/array [0 0 0 0] :u32))]
    (ak/for [(ak/* item) (ak/& items) index (az/range 0)]
      (ak/= @item (ak/intCast index)))
    (is (values= [0 1 2 3] (az/value items))))
  (with-open [total (ak/var 0 :u32)
              lefts (az/array [1 2 3] :u32)
              rights (az/array [10 20 30] :u32)]
    (ak/for [left lefts right rights]
      (ak/+= total (ak/* left right)))
    (is (values= 140 (az/value total))))
  (with-open [total (ak/var 0 :usize)]
    (ak/for [i (az/range 2 5)]
      (ak/+= total i))
    (is (values= 9 (az/value total)))))

(deftest direct-imported-function-results-and-output
  (let [err (StringWriter.)]
    (binding [*err* err]
      (is (nil? (debug/print "Hello, {s}!\n" ["World"])))
      (is (nil? (debug/print "Hello, {s}!\n" ["Clojure"]))))
    (is (values= "Hello, World!\nHello, Clojure!\n" (str err))))
  (is (values= 3.0 (math/sqrt 9.0)))
  (is (values= 4.0 (math/sqrt 16.0)))
  (is (Double/isNaN (az/value (math/sqrt -1.0))))
  (let [adapters-before (count @@#'native-call/prepared-adapters)]
    (is (values= 5.0 (math/sqrt 25.0)))
    (is (values= adapters-before (count @@#'native-call/prepared-adapters))
        "changing an ordinary argument reuses the typed adapter")))

(deftest value-expressions-use-the-shared-native-call-bridge
  (is (true? (ak/== \e (az/char-literal "'\\x65'"))))
  (is (true? (ak/== \é (az/char-literal "'\\u{e9}'"))))
  (is (values= (mapv int "hello") @(az/string-literal "\"h\\x65llo\"")))
  (let [err (StringWriter.)]
    (binding [*err* err]
      (debug/print "{}\n" [(mem/eql :u8 "hello" (az/string-literal "\"h\\x65llo\""))]))
    (is (values= "true\n" (str err))))
  (let [err (StringWriter.)]
    (binding [*err* err]
      (is (nil? (debug/print "{}\n{}\n{}\n"
                             [(and true false) (or true false) (ak/! true)]))))
    (is (values= "false\ntrue\nfalse\n" (str err))))
  (with-open [absent (ak/as nil [:optional [:slice-const :u8]])
              present (ak/as "hi" [:optional [:slice-const :u8]])]
    (is (true? (ak/== absent nil)))
    (is (false? (ak/!= absent nil)))
    (is (true? (ak/!= present nil)))
    (is (false? (ak/== present nil)))
    (is (nil? (debug/assert (ak/== absent nil))))
    (is (nil? (debug/assert (ak/!= present nil)))))
  (is (values= 3.0 (ak/sqrt 9.0)))
  (is (values= 4 (ak/sizeOf ak/i32)))
  (let [type (ak/TypeOf true)
        err (StringWriter.)]
    (is (az/zig-type? type))
    (is (values= "bool" (:zig-name (value/type-info type))))
    (is (values= 1 (ak/sizeOf type)))
    (binding [*err* err]
      (debug/print "type: {}\n" [type]))
    (is (values= "type: bool\n" (str err))))
  (let [type (ak/Vector 4 ak/i32)]
    (is (az/zig-type? type))
    (is (values= 16 (ak/sizeOf type))))
  (is (values= 30 (ak/+% 10 20)))
  (is (values= 2 (ak/min 2 7)))
  (is (values= 42 (az/field {:answer 42} :answer)))
  (is (values= 20 (az/index [10 20 30] 1)))
  (let [before (count @@#'native-call/prepared-adapters)]
    (is (values= 50 (ak/+% 20 30)))
    (is (values= before (count @@#'native-call/prepared-adapters))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Wrong number"
                        (ak/! true false)))
  (let [error (try (ak/! 42) (catch clojure.lang.Compiler$CompilerException e e))]
    (is (some? error))
    (is (str/includes? (:aguafria/report (runtime/error-data error))
                       "expected type 'bool'"))))

(deftest mutable-native-values-in-ordinary-clojure-let
  (with-open [optional-value (ak/var (ak/as nil [:optional [:slice-const :u8]]))]
    (debug/assert (ak/== optional-value nil))
    (ak/= optional-value "hi")
    (debug/assert (ak/!= optional-value nil))
    (let [err (StringWriter.)]
      (binding [*err* err]
        (debug/print "{?s}\n" [optional-value]))
      (is (values= "hi\n" (str err))))
    (ak/= optional-value nil)
    (is (ak/== optional-value nil)))
  (with-open [number (ak/var 1 :i32)]
    (ak/= number 42)
    (is (values= 42 @number))
    (is (ak/== number 42))
    (ak/+= number 8)
    (is (values= 50 @number))
    (ak/*= number 2)
    (is (values= 100 @number))
    (ak/-= number 1)
    (is (values= 99 @number)))
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defn optional-mutation :bool []
                 (let [value (ak/var (ak/as nil [:optional [:slice-const :u8]]))]
                   (ak/= value "hi")
                   (ak/!= value nil)))))
      (is (true? ((ns-resolve namespace 'optional-mutation))))
      (finally (remove-ns (ns-name namespace))))))

(deftest void-error-unions-return-errors-and-success-to-the-jvm
  (doseq [type [:!void [:! :void] [:error-union :anyerror :void]
                [:error-union [:error-set [:DemoError]] :void]]]
    (with-open [failed (ak/as (az/error-value :DemoError) type)
                succeeded (ak/as nil type)
                mutable (ak/var (az/error-value :DemoError) type)]
      (is (values= :DemoError (get-in @failed [:error :name])))
      (is (values= {:ok nil} @succeeded))
      (is (values= :DemoError (get-in @mutable [:error :name])))
      (ak/= mutable {:ok nil})
      (is (values= {:ok nil} @mutable)))))

(deftest inferred-member-literals-construct-native-values
  (let [namespace (fixture)
        list-type (std/ArrayList :u21)]
    (try
      (binding [*ns* namespace]
        (require '[aguafria.std :as std] '[aguafria.std.heap :as heap])
        (eval '(az/defstruct Threshold
                 [[:minimum :f32]
                  [:maximum :f32]
                  (az/const-decl default {:attrs #{:public}} Threshold
                                 {:minimum 0.25 :maximum 0.75})]))
        (eval '(az/defenum Mode [:active :inactive]))
        (eval '(az/defn append-and-read :!u21 [[initial (std/ArrayList :u21)]]
                 (let [list (ak/var initial)]
                   (ak/defer ((az/field list :deinit) heap/page_allocator))
                   (try ((az/field list :append) heap/page_allocator \☔))
                   (az/index (az/field list :items) 0)))))
      (with-open [list (ak/var :.empty list-type)
                  appended ((ns-resolve namespace 'append-and-read) list)]
        (is (values= :var (:kind (value/info list))))
        (is (values= 0 (az/field list :capacity)))
        (is (values= 0 (az/field (az/field list :items) :len)))
        (is (values= {:ok (int \☔)} @appended)))
      (let [threshold-type @(ns-resolve namespace 'Threshold)
            mode-type @(ns-resolve namespace 'Mode)]
        (with-open [threshold (threshold-type :.default)
                    mutable-threshold (ak/var :.default threshold-type)
                    mode (ak/var :.active mode-type)]
          (is (values= {:minimum 0.25 :maximum 0.75} @threshold))
          (is (values= @threshold @mutable-threshold))
          (is (true? (ak/== mode :.active)))
          (ak/= mode :.inactive)
          (is (true? (ak/== mode :.inactive)))))
      (finally (remove-ns (ns-name namespace))))))

(deftest bound-container-methods-preserve-native-receivers
  (with-open [list (ak/var :.empty (std/ArrayList :u21))]
    (let [append (az/field list :append)
          allocator zig-testing/allocator]
      (try
        (is (fn? append))
        (is (values= {:ok nil} (append allocator \☔)))
        (is (values= {:ok nil} (array-list/append list allocator \☺)))
        (is (values= [9748 9786] (az/field list :items)))
        (is (values= [9748 9786] (:items @list)))
        (is (values= (az/field list :capacity) (:capacity @list)))
        (is (str/starts-with? (pr-str list)
                              "#aguafria.zig.value.ZigValue[{:items [9748 9786]"))
        (is (values= {:ok nil} (zig-testing/expectEqual 2 (az/field (az/field list :items) :len))))
        (finally (array-list/deinit list allocator)))))
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defstruct Counter
                 [[:value :i32]
                  (az/fn increment :void
                    [[self [:* Counter]] [amount :i32]]
                    (ak/+= (az/field self :value) amount))])))
      (let [Counter @(ns-resolve namespace 'Counter)]
        (with-open [counter (ak/var (Counter {:value 1}))]
          (let [increment (az/field counter :increment)]
            (is (nil? (increment 4)))
            (is (nil? (increment 6)))
            (is (values= 11 (az/field counter :value))))))
      (finally (remove-ns (ns-name namespace))))))

(deftest container-functions-construct-their-own-type
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defstruct Timestamp
                 [[:seconds :i64] [:nanos :u32]
                  (az/fn- epoch-seconds :i64 [] 0)
                  (az/fn unix-epoch Timestamp "The Unix epoch." []
                    (Timestamp {:seconds (epoch-seconds) :nanos 0}))]))
        (eval '(az/defn read-epoch-seconds :i64 []
                 (az/field ((az/field Timestamp :unix-epoch)) :seconds))))
      (is (values= 0 ((ns-resolve namespace 'read-epoch-seconds))))
      (let [Timestamp @(ns-resolve namespace 'Timestamp)]
        (with-open [timestamp (Timestamp {:seconds 123 :nanos 456})]
          (is (values= 123 (az/field timestamp :seconds)))
          (is (values= 456 (az/field timestamp :nanos)))))
      (is (str/includes? (:doc (meta (ns-resolve namespace 'Timestamp))) "The Unix epoch."))
      (finally (remove-ns (ns-name namespace))))))

(deftest container-state-is-shared-by-jvm-and-native-calls
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defstruct S
                 [[:value {:var 1234 :doc "Shared counter."} :i32]
                  [:other {:var 7} :i32]
                  [:limit {:const 9000} :i32]
                  [:instance {:default 12} :i32]]))
        (eval '(az/defn bump :i32 []
                 (ak/+= (az/field S :value) 1)
                 (az/field S :value))))
      (let [S @(ns-resolve namespace 'S)
            bump (ns-resolve namespace 'bump)]
        (with-open [counter (az/field S :value)]
          (is (values= 1234 @counter))
          (is (nil? (ak/+= (az/field S :value) 1)))
          (is (values= 1235 @counter))
          (is (values= 1236 (bump)))
          (is (values= 1236 @counter))
          (with-open [other (az/field S :other)]
            (is (values= 7 @other)))
          (is (values= 1237 (bump)) "another member adapter must not reset state")
          (is (values= 1237 @counter))
          (ak/= counter 42)
          (is (values= 43 (bump)))
          (is (values= 43 @(az/field S :value)))
          (binding [*ns* namespace]
            (eval '(az/defn bump :i32 []
                     (ak/+= (az/field S :value) 2)
                     (az/field S :value))))
          (is (values= 45 (bump)) "hot reload retains the existing container state")
          (is (values= 45 @counter)))
        (is (values= 9000 (az/field S :limit)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mutable native value"
                              (ak/+= (az/field S :limit) 1)))
        (is (values= 4 (ak/sizeOf S)) "only instance fields occupy struct storage")
        (with-open [instance (S {})]
          (is (values= 12 (az/field instance :instance)))))
      (finally (remove-ns (ns-name namespace))))))

(deftest thread-local-values-resolve-on-the-calling-platform-thread
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defvar counter :i32 {:attrs #{ak/threadlocal}} 1234))
        (eval '(az/defn bump-tls :i32 [] (ak/+= counter 1) counter)))
      (let [counter @(ns-resolve namespace 'counter)
            bump (ns-resolve namespace 'bump-tls)]
        (is (values= 1234 @counter))
        (ak/+= counter 1)
        (is (values= 1236 (bump)))
        (is (values= 1236 @counter))
        (let [other (future
                      (let [before @counter]
                        (ak/+= counter 10)
                        [before (bump) @counter]))]
          (is (values= [1234 1245 1245] @other)))
        (is (values= 1236 @counter)))
      (finally (remove-ns (ns-name namespace))))))

(deftest named-union-constructors-work-from-the-jvm
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defunion Payload
                 "A native union constructor."
                 {:attrs #{ak/enum}}
                 [[:int {:doc "Integer payload."} :i32]
                  [:float :f64]
                  (az/fn answer :i32 [] 42)])))
      (let [Payload @(ns-resolve namespace 'Payload)
            payload (ak/var (Payload {:int 7}))]
        (is (value/zig-value? payload))
        (is (values= 7 (:int payload)))
        (is (values= 42 ((:answer Payload))))
        (ak/= payload (Payload {:float 12.5}))
        (is (values= 12.5 (:float payload)))
        (let [description (az/describe payload)]
          (is (= :union (:kind description)))
          (is (= #{:int :float} (set (map :name (:fields description)))))))
      (finally (remove-ns (ns-name namespace))))))

(deftest anonymous-container-members-work-from-the-jvm
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (let [type (eval '(az/struct [[:value {:var 1234} :i32]]))
              field (az/field type :value)]
          (is (values= :struct (:kind (value/type-info type))))
          (is (values= 'value (-> type value/type-info :members first :name)))
          (is (values= 1234 @field))
          (ak/+= field 1)
          (is (values= 1235 @(az/field type :value))))
        (let [type (eval '(let [T :i32]
                            (az/struct [[:x T]])))
              instance (type {:x 7})]
          (is (values= 7 (az/field instance :x))))
        (is (values= :enum (:kind (value/type-info (eval '(az/enum [:red :blue]))))))
        (is (values= :union (:kind (value/type-info (eval '(az/union [[:number :i32]])))))))
      (finally (remove-ns (ns-name namespace))))))

(deftest variable-types-can-be-inferred-without-a-placeholder
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defvar mouse-down false))
        (eval '(az/defvar documented "An inferred flag." {:public false} true))
        (eval '(az/defvar local-flag {:attrs #{ak/threadlocal}} false))
        (eval '(az/defvar count :u32 {:public false} 12))
        (eval '(az/defn pressed :bool [] mouse-down)))
      (let [mouse-down @(ns-resolve namespace 'mouse-down)
            pressed (ns-resolve namespace 'pressed)]
        (is (false? @mouse-down))
        (is (false? (pressed)))
        (ak/= mouse-down true)
        (is (true? (pressed)))
        (is (true? @@(ns-resolve namespace 'documented)))
        (is (false? @@(ns-resolve namespace 'local-flag)))
        (is (values= 12 @@(ns-resolve namespace 'count)))
        (is (nil? (:type (:aguafria/declaration (meta (ns-resolve namespace 'mouse-down)))))))
      (finally (remove-ns (ns-name namespace))))))

(deftest attribute-sets-work-in-native-and-jvm-declarations
  (with-open [number (ak/var 1 :i32 {:attrs #{ak/comptime}})]
    (ak/+= number 1)
    (is (values= 2 @number)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":attrs must be a set"
                        (ak/var 1 :i32 {:attrs ak/comptime})))
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (is (thrown? Exception
                     (eval '(az/defvar bad {:attrs #{ak/threadlocal}} :i32 1))))
        (is (thrown? Exception
                     (eval '(az/defvar bad :i32 {:attrs ak/threadlocal} 1))))
        (eval '(az/defn compile-counter :i32 []
                 (let [counter (ak/var 1 :i32 {:attrs #{ak/comptime}})]
                   (ak/+= counter 1)
                   counter))))
      (is (values= 2 ((ns-resolve namespace 'compile-counter))))
      (finally (remove-ns (ns-name namespace))))))

(deftest generic-method-vars-have-real-completion-metadata
  (is (values= '([self gpa item]) (:arglists (meta #'array-list/append))))
  (is (str/includes? (:doc (meta #'array-list/append)) "Extend the list"))
  (is (:receiver-method? (:aguafria/zig-reference (meta #'array-list/append))))
  (is (str/includes? (slurp (io/resource "aguafria/std/ArrayList.clj"))
                     "clojure.core/declare"))
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (require '[aguafria.std :as std]
                 '[aguafria.std.ArrayList :as array-list]
                 '[aguafria.std.testing :as testing])
        (eval '(az/deftest method-vars-work-in-native-code
                 (let [list (ak/var :.empty (std/ArrayList :u21))]
                   (ak/defer (array-list/deinit list testing/allocator))
                   (try (array-list/append list testing/allocator \☔))
                   (try (testing/expectEqual 1 (az/field (az/field list :items) :len)))))))
      (is (values= :passed (:status ((ns-resolve namespace 'method-vars-work-in-native-code)))))
      (finally (remove-ns (ns-name namespace))))))

(deftest field-accessor-vars-work-in-jvm-and-native-code
  (is (values= '([self]) (:arglists (meta #'array-list/-items))))
  (is (str/includes? (:doc (meta #'array-list/-items)) "Contents of the list"))
  (is (str/includes? (:doc (meta #'array-list/-items)) "items: aguafria.std.ArrayList/Slice"))
  (is (str/includes? (:doc (meta #'array-list/Slice)) "pub const Slice = if (alignment)"))
  (is (str/includes? (:doc (meta #'std/ArrayList)) "Fields:"))
  (is (str/includes? (:doc (meta #'std/ArrayList)) "aguafria.std.ArrayList/-items"))
  (is (values= '([self]) (:arglists (meta #'array-list-slice/-len))))
  (is (values= "list.items.len"
               (az/emit-expr '(aguafria.std.ArrayList.Slice/-len (aguafria.std.ArrayList/-items list)))))
  (with-open [slice (ak/as [1 2] [:slice :u21])]
    (is (values= 2 (array-list-slice/-len slice)))
    (let [pointer (array-list-slice/-ptr slice)]
      (is (value/zig-value? pointer))
      (is (value/zig-pointer? (az/value pointer)))))
  (is (:field-accessor? (:aguafria/zig-reference (meta #'array-list/-items))))
  (is (values= "list.items" (az/emit-expr '(aguafria.std.ArrayList/-items list))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one receiver"
                        (array-list/-items)))
  (let [version (:ok (semantic-version/parse "1.2.3"))]
    (is (values= [1 2 3] [(semantic-version/-major version)
                          (semantic-version/-minor version)
                          (semantic-version/-patch version)])))
  (with-open [list (ak/var :.empty (std/ArrayList :u21))]
    (try
      (is (values= [] (array-list/-items list)))
      (is (values= 0 (array-list/-capacity list)))
      (array-list/append list zig-testing/allocator \☔)
      (is (values= [9748] (array-list/-items list)))
      (is (values= 1 (-> list array-list/-items array-list-slice/-len)))
      (is (values= (az/field list :capacity) (array-list/-capacity list)))
      (finally (array-list/deinit list zig-testing/allocator))))
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (require '[aguafria.std :as std]
                 '[aguafria.std.ArrayList :as array-list]
                 '[aguafria.std.ArrayList.Slice :as array-list-slice]
                 '[aguafria.std.testing :as testing])
        (eval '(az/deftest field-vars-work-in-native-code
                 (let [list (ak/var :.empty (std/ArrayList :u21))]
                   (ak/defer (array-list/deinit list testing/allocator))
                   (try (array-list/append list testing/allocator \☔))
                   (try (testing/expectEqual 1 (-> list array-list/-items array-list-slice/-len)))
                   (try (testing/expect (> (array-list/-capacity list) 0)))))))
      (is (values= :passed (:status ((ns-resolve namespace 'field-vars-work-in-native-code)))))
      (finally (remove-ns (ns-name namespace))))))

(deftest invalid-private-function-fails-at-its-own-definition
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (let [failure (try
                        (eval '(az/defn- change-constant :void []
                                 (let [constant 5678]
                                   (ak/+= constant 1))))
                        (catch clojure.lang.Compiler$CompilerException error error))]
          (is (some? failure))
          (is (str/includes? (or (:aguafria/report (runtime/error-data failure)) "")
                             "cannot assign to constant"))
          (is (nil? (ns-resolve namespace 'main)))))
      (finally (remove-ns (ns-name namespace))))))

(deftest undefined-storage-and-destructuring-from-the-jvm
  (with-open [x (ak/var ak/undefined :u32)
              y (ak/var ak/undefined :u32)
              z (ak/var ak/undefined :u32)
              numbers (az/array [4 5 6] :u32)
              lanes (ak/as [7 8 9] [:vector 3 :u32])]
    ;; Never read undefined storage. Initialize it before observing its contents.
    (ak/= [x y z] [1 2 3])
    (is (values= [1 2 3] (mapv deref [x y z])))
    (ak/= [x y z] numbers)
    (is (values= [4 5 6] (mapv deref [x y z])))
    (is (values= [:array 3 :u32] (value/type numbers)))
    (ak/= [x y z] lanes)
    (is (values= [7 8 9] (mapv deref [x y z])))
    (ak/= [x y] [y x])
    (is (values= [8 7] (mapv deref [x y])))
    (ak/= [:_ x :_] [1 2 3])
    (is (values= 2 @x))
    (is (nil? (ak/= :_ numbers)))))

(deftest explicit-initializers-use-value-first-on-the-jvm
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defstruct Point [[:x :i32] [:y :i32]])))
      (let [point-type @(ns-resolve namespace 'Point)]
        (with-open [point (az/init {:x 20 :y 22} point-type)]
          (is (values= {:x 20 :y 22} @point))))
      (finally (remove-ns (ns-name namespace))))))

(deftest native-array-destructuring-body-runs-on-jvm-and-zig
  (let [namespace (fixture)
        body '(let [position (az/array [1 2] :i32)
                    [x y] position
                    orange (az/array [255 165 0 255] :u8)]
                (debug/print "x = {}, y = {}\n" [x y])
                (debug/print "{any}\n" [(swizzle-rgba-to-bgra orange)]))]
    (try
      (binding [*ns* namespace]
        (eval '(az/defn- swizzle-rgba-to-bgra [:array 4 :u8]
                 [[rgba [:array 4 :u8]]]
                 (let [[red green blue alpha] rgba]
                   [blue green red alpha])))
        (let [output (StringWriter.)]
          (binding [*err* output] (eval body))
          (is (values= "x = 1, y = 2\n{ 0, 165, 255, 255 }\n" (str output))))
        (eval (list 'az/defn 'main :void [] body)))
      (let [output (StringWriter.)]
        (binding [*err* output] ((ns-resolve namespace 'main)))
        (is (values= "x = 1, y = 2\n{ 0, 165, 255, 255 }\n" (str output))))
      (finally (remove-ns (ns-name namespace))))))

(deftest destructuring-then-mutable-rebinding-preserves-clojure-semantics
  (let [namespace (fixture)
        body '(let [tuple [1 2 3]
                    [x y z] tuple
                    x (ak/var x :u32)
                    y (ak/var y :u32)]
                (ak/= y 100)
                (ak/= [:_ x :_] tuple)
                (debug/print "{} {} {}\n" [x y z]))]
    (try
      (binding [*ns* namespace]
        (let [output (StringWriter.)]
          (binding [*err* output] (eval body))
          (is (values= "2 100 3\n" (str output))))
        (eval (list 'az/defn 'mixed :void [] body)))
      (let [output (StringWriter.)]
        (binding [*err* output] ((ns-resolve namespace 'mixed)))
        (is (values= "2 100 3\n" (str output))))
      (finally (remove-ns (ns-name namespace))))))

(deftest destructuring-retains-native-struct-error-and-slice-values
  (let [namespace (fixture)
        body '(let [tuple [(Point {:x 7})
                           (az/field Fault :Broken)
                           (ak/as "hello" [:slice-const :u8])]
                    [point fault text] tuple
                    point (ak/var point)]
                (ak/= point (Point {:x 9}))
                (debug/print "{} {} {s}\n" [(az/field point :x) fault text]))]
    (try
      (binding [*ns* namespace]
        (eval '(az/defstruct Point [[:x :i32]]))
        (eval '(az/defconst Fault (az/type [:error-set [:Broken]])))
        (let [output (StringWriter.)]
          (binding [*err* output] (eval body))
          (is (values= "9 error.Broken hello\n" (str output))))
        (eval (list 'az/defn 'mixed-native-values :void [] body)))
      (let [output (StringWriter.)]
        (binding [*err* output] ((ns-resolve namespace 'mixed-native-values)))
        (is (values= "9 error.Broken hello\n" (str output))))
      (finally (remove-ns (ns-name namespace))))))

(deftest nested-rebinding-keeps-outer-values
  (let [namespace (fixture)
        body '(let [x (ak/i32 7)
                    y (let [x (ak/i32 9)] x)]
                (+ x y))]
    (try
      (binding [*ns* namespace]
        (is (values= 16 (eval body)))
        (eval (list 'az/defn 'nested :i32 [] body)))
      (is (values= 16 ((ns-resolve namespace 'nested))))
      (finally (remove-ns (ns-name namespace))))))

(deftest zig-source-prints-declarations-types-and-values
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace runtime/*source-only-registration?* true]
        (eval '(az/defstruct Point "A point." [[:x :i32]]))
        (eval '(az/defconst limit :u32 42))
        (eval '(az/defvar counter :u32 7))
        (eval '(az/defn add :i32 [[x :i32]] (+ x 1)))
        (eval '(az/deftest addition-test (debug/assert true))))
      (doseq [[name expected] [['Point "/// A point."]
                               ['limit "const limit: u32 = 42;"]
                               ['counter "var counter: u32 = 7;"]
                               ['add "fn add(x: i32)"]
                               ['addition-test "test \"addition-test\""]]]
        (is (str/includes? (with-out-str (az/zig-source! (ns-resolve namespace name)))
                           expected)))
      (is (str/includes? (with-out-str (az/zig-source! @(ns-resolve namespace 'Point)))
                         "const Point = struct"))
      (is (values= "?[]const u8\n" (with-out-str (az/zig-source! [:optional [:slice-const :u8]]))))
      (is (values= "i32\n" (with-out-str (az/zig-source! ak/i32))))
      (is (values= "[2]i32\n" (with-out-str (az/zig-source! [:array 2 ak/i32]))))
      (is (values= "[2]Point\n" (with-out-str
                                  (az/zig-source! [:array 2 @(ns-resolve namespace 'Point)]))))
      (is (values= "42\n" (with-out-str (az/zig-source! 42))))
      (is (values= "@import(\"std\").debug.print\n"
                   (with-out-str (az/zig-source! #'debug/print))))
      (with-open [items (ak/as [4 5 6] [:array 3 :u32])]
        (is (values= "@as([3]u32, .{ 4, 5, 6 })\n"
                     (with-out-str (az/zig-source! items)))))
      (is (nil? (binding [*out* (StringWriter.)] (az/zig-source! :bool))))
      (finally (remove-ns (ns-name namespace))))))

(deftest native-errors-preserve-type-across-jvm-calls
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defconst ExampleErrorSet
                 (az/type [:error-set [:ExampleErrorVariant]]))))
      (let [error-type @(ns-resolve namespace 'ExampleErrorSet)
            error-value (az/field error-type :ExampleErrorVariant)
            err (StringWriter.)]
        (is (value/zig-error? error-value))
        (with-open [number-or-error (-> error-value
                                        (ak/as [:error-union error-type :i32])
                                        ak/var)]
          (binding [*err* err]
            (debug/print "{!}\n" [number-or-error])
            (ak/= number-or-error 1234)
            (debug/print "{!}\n" [number-or-error]))
          (is (values= "error.ExampleErrorVariant\n1234\n" (str err)))
          (is (values= {:ok 1234} @number-or-error))))
      (finally (remove-ns (ns-name namespace))))))

(deftest explicit-type-constants-are-inspectable-without-native-storage
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace runtime/*source-only-registration?* true]
        (eval '(az/defconst ExampleErrorSet "Expected failures."
                 (az/type [:error-set [:Missing :Denied]])))
        (eval '(az/defconst Buffer (az/type [:array 8 :u8]))))
      (let [error-var (ns-resolve namespace 'ExampleErrorSet)
            descriptor (value/type-info @error-var)]
        (is (az/zig-type? @error-var))
        (is (values= :error-set (:kind descriptor)))
        (is (values= [:Missing :Denied] (mapv :name (:members descriptor))))
        (is (str/includes? (pr-str @error-var) ":Missing"))
        (is (str/includes? (:doc (meta error-var)) "Expected failures."))
        (is (str/includes? (:doc (meta error-var)) ":Denied"))
        (is (values= [:array 8 :u8]
                     (:type (value/type-info @(ns-resolve namespace 'Buffer))))))
      (finally (remove-ns (ns-name namespace))))))

(deftest inferred-number-constants-are-readable-from-ordinary-clojure
  (let [namespace (fixture)]
    (binding [*ns* namespace]
      (require '[aguafria.std.math :as math])
      (doseq [form '[(az/defconst octal-int (az/number-literal "0o755"))
                     (az/defconst binary-int (az/number-literal "0b11110000"))
                     (az/defconst one-billion (az/number-literal "1_000_000_000"))
                     (az/defconst binary-mask (az/number-literal "0b1_1111_1111"))
                     (az/defconst permissions (az/number-literal "0o7_5_5"))
                     (az/defconst big-address (az/number-literal "0xFF80_0000_0000_0000"))
                     (az/defconst inf (math/inf :f32))
                     (az/defconst negative-inf (- (math/inf :f64)))
                     (az/defconst nan (math/nan :f128))
                     (az/defconst computed (+ 40 2))]]
        (eval form)))
    (let [read-constant #(value/value @(ns-resolve namespace %))]
      (is (values= [493 240 1000000000 511 493 18410715276690587648N 42]
                   (mapv read-constant '[octal-int binary-int one-billion binary-mask
                                         permissions big-address computed])))
      (is (values= Double/POSITIVE_INFINITY (read-constant 'inf)))
      (is (values= Double/NEGATIVE_INFINITY (read-constant 'negative-inf)))
      (is (Double/isNaN (read-constant 'nan)))
      (is (values= 18410715276690587648N
                   (ak/as @(ns-resolve namespace 'big-address) :u64)))
      (is (str/includes? (pr-str @(ns-resolve namespace 'inf)) "##Inf")))))

(deftest inferred-aggregate-constants-retain-their-native-type
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defstruct Point [[:x :i32] [:y :i32]]))
        (eval '(az/defconst letters (az/array [\h \i] :u8)))
        (eval '(az/defconst numbers (az/array [7 9] :i32)))
        (eval '(az/defconst points
                 (az/array [(Point {:x 2 :y 3})] Point)))
        (eval '(az/defconst text "hi")))
      (let [letters @(ns-resolve namespace 'letters)
            numbers @(ns-resolve namespace 'numbers)
            points @(ns-resolve namespace 'points)
            text @(ns-resolve namespace 'text)]
        (doseq [v [letters numbers points text]]
          (is (some? (value/type v)))
          (is (str/includes? (pr-str v) "ZigValue")))
        (is (values= [104 105] @letters))
        (is (values= [7 9] (vec (seq numbers))))
        (is (values= [{:x 2 :y 3}] @points))
        (is (values= 9 (az/index numbers 1)))
        (is (nil? (debug/assert (mem/eql :u8 text "hi"))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mutable"
                              (value/set-value! numbers [1 2]))))
      (finally (remove-ns (ns-name namespace))))))

(deftest array-construction-accepts-characters-with-range-checking
  (with-open [letters (az/array [\h \i] :u8)
              unicode (az/array [\☔] :u21)]
    (is (values= [104 105] @letters))
    (is (values= [9748] @unicode))
    (is (values= 209 (reduce + 0 letters))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"out of range"
                        (az/array [\☔] :u8))))

(deftest native-loop-captures-are-eager-and-mutable
  (with-open [sum (ak/var 0 :i32)
              numbers (az/array [1 2 3] :i32)]
    (ak/for [item numbers]
      (ak/+= sum item))
    (is (values= 6 @sum))
    (ak/while (ak/< sum 9)
      (ak/+= sum 1))
    (is (values= 9 @sum)))
  (is (values= 209 (ak/+ \h \i)))
  (is (values= 6 (ak/* 2 3))))

(deftest untyped-jvm-integers-remain-lossless-beside-native-unsigned-values
  (with-open [unsigned (ak/var 532 :usize)]
    (is (values= {:ok nil} (zig-testing/expectEqual 532 unsigned)))
    (is (true? (ak/== 532 unsigned)))
    (is (false? (ak/== -1 unsigned)))
    (is (true? (ak/< -1 unsigned))))
  (with-open [unsigned (ak/var 18446744073709551615N :u64)]
    (is (false? (ak/== -1 unsigned)))
    (is (true? (ak/> unsigned Long/MAX_VALUE)))))

(deftest extern-linking-is-lazy-but-native-body-validation-is-not
  (doseq [already-running? [false true]]
    (let [namespace (fixture)]
      (binding [*ns* namespace]
        (when already-running?
          (eval '(az/defn running :i32 [] 42))
          (is (values= 42 ((ns-resolve namespace 'running)))))
        (eval '(az/defextern aguafria_missing_test_symbol :i32 [[x :i32]]))
        (eval '(az/defn use-external :i32 [[x :i32]]
                 (aguafria_missing_test_symbol x)))
        (is (:source-only? (runtime/module-info (ns-name namespace))))
        (let [failure (try
                        (eval '(az/defn invalid :i32 [[a :i32] [b :i32]] (/ a b)))
                        (catch Throwable failure failure))]
          (is (str/includes? (:aguafria/report (runtime/error-data failure))
                             "signed integers must use")))
        (let [failure (try
                        ((ns-resolve namespace 'use-external) 1)
                        (catch Throwable failure failure))]
          (is (str/includes? (:aguafria/report (runtime/error-data failure))
                             "aguafria_missing_test_symbol")))))))

(deftest ordinary-function-values-can-spawn-and-join-native-threads
  (let [namespace (fixture)]
    (binding [*ns* namespace]
      (require '[aguafria.std.Thread :as thread])
      (eval '(az/defn worker :void [] (debug/assert true)))
      (let [result (eval '(try (thread/spawn {} worker [])))]
        (is (value/zig-value? (:ok result)))
        (with-open [thread (:ok result)]
          (is (str/includes? (pr-str thread) "ZigValue"))
          (is (nil? ((az/field thread :join)))))))))

(deftest generic-private-functions-are-ordinary-callable-vars
  (let [namespace (fixture)]
    (binding [*ns* namespace]
      (eval '(az/defn- maximum T
               [[T {:zig/prefix "comptime"} :type] [left T] [right T]]
               (if (== T :bool)
                 (or left right)
                 (if (> left right) left right)))))
    (let [maximum (ns-resolve namespace 'maximum)]
      (is (true? (maximum :bool false true)))
      (is (false? (maximum :bool false false)))
      (is (values= 8 (maximum :i32 3 8)))
      (is (values= 12 (maximum :i32 12 4)))
      (is (true? (.invoke ^clojure.lang.IFn maximum :bool false true)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Wrong number"
                            (maximum :bool true)))
      (binding [*ns* namespace]
        (eval '(az/defn- maximum T
                 [[T {:zig/prefix "comptime"} :type] [left T] [right T]]
                 (if (== T :bool)
                   (and left right)
                   (if (< left right) left right)))))
      (is (false? (maximum :bool false true)))
      (is (values= 3 (maximum :i32 3 8))))))

(deftest generic-calls-use-live-native-state
  (let [namespace (fixture)]
    (binding [*ns* namespace]
      (eval '(az/defvar calls :i64 0))
      (eval '(az/defn count-call :i64 [[T {:zig/prefix "comptime"} :type] [x T]]
               (ak/= :_ x)
               (ak/= calls (+ calls 1))
               calls))
      (eval '(az/defn count-now :i64 [] calls)))
    (let [count-call (ns-resolve namespace 'count-call)
          count-now (ns-resolve namespace 'count-now)]
      (is (values= 1 (count-call :bool true)))
      (is (values= 2 (count-call :bool false)))
      (is (values= 2 (count-now))))))

(deftest native-output-is-restored-after-failure
  (let [err (StringWriter.)]
    (binding [*err* err]
      (is (thrown-with-msg? Exception #"deliberate"
                            (native-call/call-with-output
                             #(do (debug/print "before\n" [])
                                  (throw (Exception. "deliberate"))))))
      (debug/print "after\n" []))
    (is (values= "before\nafter\n" (str err)))))

(deftest buffered-c-output-is-flushed-and-restored-after-failure
  (let [out (StringWriter.)
        failure (ex-info "deliberate C capture failure" {})]
    (binding [*out* out]
      (is (identical? failure
                      (try
                        (native-call/call-with-output
                         #(do (c/printf "before\n")
                              (throw failure)))
                        (catch Throwable error error))))
      (c/printf "after\n"))
    (is (= "before\nafter\n" (str out)))))

(deftest normal-zero-arg-function-forwards-native-output
  (let [namespace (fixture)
        err (StringWriter.)]
    (binding [*ns* namespace]
      (eval '(az/defn main :void [] (debug/print "native main\n" []))))
    (binding [*err* err]
      (is (nil? ((ns-resolve namespace 'main)))))
    (is (values= "native main\n" (str err)))))

(deftest forced-inline-runtime-result-still-folds-at-the-callsite
  (let [namespace (fixture)
        err (StringWriter.)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defn- inline-add :i32 {:zig/prefix "inline"}
                 [[left :i32] [right :i32]]
                 (debug/print "inside inline call\n" [])
                 (+ left right)))
        (eval '(az/defn main :void []
                 (when (!= (inline-add 1200 34) 1234)
                   (ak/compileError "inline result no longer folds")))))
      (binding [*err* err]
        (is (nil? ((ns-resolve namespace 'main)))))
      (is (values= "inside inline call\n" (str err)))
      (finally (remove-ns (ns-name namespace))))))

(deftest process-init-main-is-directly-callable
  (let [namespace (fixture)
        out (StringWriter.)]
    (try
      (binding [*ns* namespace]
        (require '[aguafria.std.process :as process]
                 '[aguafria.std.Io.File :as std-file])
        (eval '(az/defn main :!void [[init process/Init]]
                 (try (std-file/writeStreamingAll (std-file/stdout)
                                                  (az/field init :io)
                                                  "main in this JVM\n"))))
        (eval '(az/defn ordinary :i32 [[value :i32]] value)))
      (binding [*out* out]
        (is (nil? ((ns-resolve namespace 'main)))))
      (is (values= "main in this JVM\n" (str out)))
      (is (values= 42 ((ns-resolve namespace 'ordinary) 42)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)(arity|arguments)"
                            ((ns-resolve namespace 'ordinary))))
      (finally (remove-ns (ns-name namespace))))))

(deftest minimal-process-main-receives-argv
  (let [namespace (fixture)
        err (StringWriter.)]
    (try
      (binding [*ns* namespace]
        (require '[aguafria.std.process.Init :as process-init])
        (eval '(az/defn main :!void [[init process-init/Minimal]]
                 (let [arguments (az/field (az/field init :args) :vector)]
                   (debug/print "argc={d}; argv0={s}\n"
                                [(az/field arguments :len) (az/index arguments 0)])))))
      (binding [*err* err]
        (is (nil? ((ns-resolve namespace 'main))))
        (is (nil? ((ns-resolve namespace 'main) ["hello" "world"]))))
      (is (values= "argc=1; argv0=main\nargc=3; argv0=main\n" (str err)))
      (finally (remove-ns (ns-name namespace))))))

(deftest module-struct-results-retain-field-names
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/deffield first-value :u32))
        (eval '(az/deffield second-value :u64))
        (eval '(az/defconst Record (ak/This)))
        (eval '(az/defn make-record Record [[value :u32]]
                 (az/object [[:first-value value] [:second-value (* value 10)]]))))
      (let [result ((ns-resolve namespace 'make-record) 42)]
        (try
          (is (values= {:first-value 42 :second-value 420} (az/value result)))
          (finally (az/close! result))))
      (finally (remove-ns (ns-name namespace))))))

(deftest keyword-enum-members-are-callable-values
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defconst Mode
                 (az/container {:kind :enum :argument :c_int}
                               [(az/enum-field-decl :idle)
                                (az/enum-field-decl :running)])))
        (eval '(az/defn running? :bool [[mode Mode]] (== mode :.running))))
      (let [running? (ns-resolve namespace 'running?)]
        (is (false? (running? :idle)))
        (is (true? (running? :running)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown Zig enum member"
                              (running? :missing))))
      (finally (remove-ns (ns-name namespace))))))

(deftest quoted-native-export-is-looked-up-without-zig-syntax
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defn sentence :i32
                 {:attrs #{:export} :zig/name "@\"A complete sentence.\""}
                 [] 42)))
      (is (values= 42 ((ns-resolve namespace 'sentence))))
      (finally (remove-ns (ns-name namespace))))))

(deftest ordinary-java-program-calls-the-same-native-vars
  (let [source (io/file (io/resource "fixtures/jvm/NativeCallSmoke.java"))
        process (.start
                 (doto (ProcessBuilder.
                        ^java.util.List
                        [(str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         (.getAbsolutePath source)])
                   (.redirectErrorStream true)))
        output (slurp (.getInputStream process))]
    (is (zero? (.waitFor process)) output)
    (is (re-find #"Hello, Java!" output))
    (is (re-find #"maximum=true, sqrt=4.0, print=nil" output))))

(deftest native-panics-do-not-terminate-the-jvm
  (let [source (io/file (io/resource "fixtures/jvm/PanicSmoke.clj"))
        process (.start
                 (doto (ProcessBuilder.
                        ^java.util.List
                        [(str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" (.getAbsolutePath source)])
                   (.redirectErrorStream true)))
        output (future (slurp (.getInputStream process)))
        finished? (.waitFor process 120 java.util.concurrent.TimeUnit/SECONDS)]
    (when-not finished? (.destroyForcibly process))
    (is finished? "Native panic boundary must not hang")
    (is (and finished? (zero? (.exitValue process))) @output)
    (is (str/includes? @output "JVM survived native assertion and overflow") @output)))
