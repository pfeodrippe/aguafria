(ns aguafria.zig.value-test
  (:require [aguafria.keyword :as ak]
            [aguafria.std :as std]
            [aguafria.zig :as a]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.pprint :as pprint]
            [clojure.test :refer [deftest is]]))

(deftest scalar-type-expressions-use-the-compiler-reported-storage-type
  (with-open [arena (java.lang.foreign.Arena/ofConfined)]
    (let [storage (.allocate arena 4 4)
          declared '(aguafria.keyword/TypeOf fixture/value)
          schema {:kind :scalar :type :i32 :size 4 :alignment 4}]
      (value/write-value! storage declared schema 1060 arena)
      (is (= 1060 (.get storage java.lang.foreign.ValueLayout/JAVA_INT 0)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (value/write-value! storage declared schema 2147483648 arena))))))

(deftest float32-transport-preserves-special-values-and-finite-bounds
  (with-open [arena (java.lang.foreign.Arena/ofConfined)]
    (let [storage (.allocate arena 4 4)]
      (doseq [input [Double/POSITIVE_INFINITY Double/NEGATIVE_INFINITY Double/NaN]]
        (let [expected (unchecked-float input)
              direct (#'runtime/coerce-argument :f32 input)]
          (value/write-value! storage :f32 nil input arena)
          (is (= (Float/floatToIntBits expected) (Float/floatToIntBits direct)))
          (is (= (Float/floatToIntBits expected)
                 (Float/floatToIntBits
                  (.get storage java.lang.foreign.ValueLayout/JAVA_FLOAT 0))))))
      (is (thrown? IllegalArgumentException
                   (#'runtime/coerce-argument :f32 Double/MAX_VALUE)))
      (is (thrown? IllegalArgumentException
                   (value/write-value! storage :f32 nil Double/MAX_VALUE arena))))))

(deftest computed-scalar-types-accept-jvm-arguments-and-nested-fields
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.scalar-alias-fixture :reload))
  (let [context (the-ns 'aguafria.zig.scalar-alias-fixture)
        call (fn [name input] (a/value ((ns-resolve context name) input)))]
    (is (= 1234 (call 'echo-integer 1234)))
    (is (= false (call 'echo-boolean false)))
    (is (= 2.5 (call 'echo-float 2.5)))
    (binding [*ns* context]
      (with-open [fields (a/init {:number 7 :enabled false :ratio 2.5}
                                 'aguafria.zig.scalar-alias-fixture/Fields)]
        (is (= {:number 7 :enabled false :ratio 2.5} (a/value fields)))))))

(deftest inferred-constants-retain-the-compiler-reported-scalar-type
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.scalar-alias-fixture :reload))
  (let [context (the-ns 'aguafria.zig.scalar-alias-fixture)]
    (doseq [[name expected-type expected-value]
            [['inferred-integer :i32 1060]
             ['inferred-boolean :bool false]
             ['inferred-float :f32 1.5]]]
      (let [constant (var-get (ns-resolve context name))
            materialized (value/realize! constant)]
        (is (= {:kind :scalar :type expected-type} (:schema materialized)))
        (is (= expected-value (a/value constant)))))))

(deftest wide-float-constants-retain-native-storage
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.scalar-alias-fixture :reload))
  (let [context (the-ns 'aguafria.zig.scalar-alias-fixture)]
    (doseq [[name expected-type]
            [['half-float :f16] ['extended-float :f80] ['wide-float :f128]]]
      (let [constant (var-get (ns-resolve context name))
            materialized (value/realize! constant)]
        (is (= :native (:representation materialized)))
        (is (= expected-type (value/storage-type constant)))
        (is (= 1.5 (a/value constant)))))))

(deftest aligned-addresses-use-canonical-pointer-attributes
  (doseq [alignment [2 64]
          mutable? [false true]]
    (with-open [arena (java.lang.foreign.Arena/ofConfined)]
      (let [storage (.allocate arena 4 alignment)
            owner (value/native-value
                   {:kind (if mutable? :var :const) :type :u32}
                   (constantly {:representation :native
                                :segment storage :size 4 :alignment alignment
                                :pointer-alignment alignment
                                :schema {:kind :int :bits 32 :signed? false}}))]
        (with-open [pointer (value/address-value owner mutable?)]
          (is (= [:* (cond-> {:align alignment}
                       (not mutable?) (assoc :const? true)) :u32]
                 (value/qualified-type pointer)))
          (is (identical? owner (first (:owners (value/realize! pointer)))))
          (is (= (.address storage)
                 (.address (.get ^java.lang.foreign.MemorySegment
                            (:segment (value/realize! pointer))
                                 java.lang.foreign.ValueLayout/ADDRESS 0)))))))))

(deftest borrowed-storage-provenance-requires-type-address-and-size-identity
  (with-open [arena (java.lang.foreign.Arena/ofConfined)]
    (let [storage (.allocate arena 8 8)
          schema {:kind :error-union}
          type [:error-union [:error-set [:Rejected]] :u32]
          owner (value/native-value
                 {:kind :var :type type}
                 (constantly {:representation :native :segment storage
                              :schema schema :native-image "actual-storage"}))
          pointer (value/native-value
                   {:kind :const :type [:* type]}
                   (constantly {:owners [owner]}))]
      (doseq [[view-type view-storage matched?]
              [[type (.asSlice storage 0 8) true]
               [:u64 (.asSlice storage 0 8) false]
               [type (.asSlice storage 0 4) false]
               [type (.allocate arena 8 8) false]]]
        (let [view (value/native-value
                    {:kind :var :type view-type}
                    (constantly {:representation :native :segment view-storage}))]
          (value/retain-storage-provenance! view [pointer])
          (is (= (when matched? schema) (:schema (value/realize! view))))
          (is (= (when matched? "actual-storage") (:native-image (value/realize! view))))
          (is (identical? pointer (first (:owners (value/realize! view))))))))))

(deftest native-sequential-values-support-clojure-destructuring
  (doseq [type [[:array 3 :i32]
                [:array 3 {:sentinel 0} :i32]
                [:vector 3 :i32]
                [:slice :i32]]]
    (with-open [native (ak/var [1 2 3] type)]
      (let [[x y z missing :as original] native
            [head & tail] native]
        (is (= [1 2 3 nil] [x y z missing]))
        (is (identical? native original))
        (is (= 1 head))
        (is (= [2 3] (vec tail)))
        (is (= 3 (count native)))
        (is (= 2 (nth native 1)))
        (is (= :missing (nth native 3 :missing)))
        (is (= :missing (nth native -1 :missing)))
        (is (thrown? IndexOutOfBoundsException (nth native 3)))
        (is (thrown? IndexOutOfBoundsException (nth native -1))))
      (ak/= native [4 5 6])
      (is (= 4 (nth native 0)))))
  (with-open [grid (a/array [[1 2] [3 4]] [:array 2 :i32])
              empty-array (a/array [] :i32)]
    (let [[[a b] [c d]] grid
          [missing] empty-array]
      (is (= [1 2 3 4] [a b c d]))
      (is (nil? missing))
      (is (zero? (count empty-array)))))
  (let [closed (a/array [1] :i32)]
    (.close closed)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed" (nth closed 0)))))

(deftest native-handles-have-consistent-inspection-tags
  (doseq [[input type expected] [[42 :i32 42]
                                 [true :bool true]
                                 [2.5 :f32 2.5]
                                 [[1 2 3] [:array 3 :u16] [1 2 3]]]]
    (with-open [native (ak/var input type)]
      (let [printed (str "#aguafria.zig.value.ZigValue[" (pr-str expected) "]")]
        (is (= expected @native))
        (is (= printed (pr-str native)))
        (is (= printed (str native)))
        (is (= printed (binding [*print-dup* true] (pr-str native))))
        (is (= (str printed "\n") (with-out-str (pprint/pprint native))))))))

(deftest generated-container-inspection-uses-native-fields
  (doseq [type [:u21 :u8 [:optional :u21]]]
    (with-open [native (ak/var :.empty (std/ArrayList (a/type type)))]
      (let [items (if (= type :u8) "" [])
            expected {:items items :capacity 0 :pointer_stability {:state :unlocked}}]
        (is (= expected @native))
        (is (= (str "#aguafria.zig.value.ZigValue["
                    (pr-str expected) "]")
               (pr-str native)))))))

(deftest reflected-inspection-does-not-follow-arbitrary-pointers
  (let [context (create-ns (gensym "aguafria.inspect-fixture-"))]
    (try
      (binding [*ns* context]
        (refer 'clojure.core)
        (require '[aguafria.zig :as a] '[aguafria.keyword :as ak])
        (eval '(a/defn- Box :type [[T {:zig/prefix "comptime"} :type]]
                 (a/struct [[:item T]
                            [:next [:optional [:* :u32]]]])))
        (eval '(a/defn make-box (Box (a/type [:array 2 :u21])) []
                 (a/init {:item [9748 9786]
                          :next (ak/as (ak/ptrFromInt 4) [:* :u32])}
                         (Box (a/type [:array 2 :u21]))))))
      (with-open [native ((ns-resolve context 'make-box))]
        (let [decoded @native]
          (is (= [9748 9786] (:item decoded)))
          ;; An aligned address that must never be dereferenced by inspection.
          (is (value/zig-pointer? (:next decoded)))
          (is (= 4 (value/pointer-address (:next decoded))))
          (is (= "*u32" (value/pointer-type (:next decoded))))))
      (finally (remove-ns (ns-name context))))))
