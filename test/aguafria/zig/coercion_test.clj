(ns aguafria.zig.coercion-test
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as a]
            [aguafria.zig.emitter :as emit]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.value :as value]
            [clojure.test :refer [deftest is]]))

(deftest coercion-emission
  (doseq [[form expected]
          [['(ak/i32 (+ 1 1)) "@as(i32, (1 + 1))"]
           ['(ak/f32 (/ 7.0 3.0)) "@as(f32, (7.0 / 3.0))"]
           ['(ak/as [1 2] [:array 2 :u8]) "@as([2]u8, .{1, 2})"]
           ['(ak/as nil [:optional :u8]) "@as(?u8, null)"]
           ['(-> 42 (ak/as :i32)) "@as(i32, 42)"]
           ['ak/i32 "i32"]]]
    (is (= expected (emit/emit-expr (the-ns 'aguafria.zig.coercion-test) form))))
  (is (thrown? Exception (emit/emit-expr '(ak/i32 1 2))))
  (is (thrown? Exception (emit/emit-expr '(ak/undefined 1)))))

(deftest primitive-constructors-retain-native-values
  (is (= 2 (a/value (ak/i32 (+ 1 1)))))
  (is (value/zig-value? (ak/i32 2)))
  (is (= :i32 (value/qualified-type (ak/i32 2))))
  (is (= (float (/ 7.0 3.0)) (float (a/value (ak/f32 (/ 7.0 3.0))))))
  (is (value/zig-value? (ak/f32 2.5)))
  (is (= :f32 (value/qualified-type (ak/f32 2.5))))
  (is (= 2.5 (a/value (ak/f64 2.5))))
  (is (= 42 (a/value (ak/as 42 :c_int))))
  (is (= 42 (a/value (ak/c_int 42))))
  (is (nil? (ak/as nil :void)))
  (is (= 42 (a/value (ak/comptime_int 42))))
  (is (= 2.5 (a/value (ak/comptime_float 2.5))))
  (is (true? (ak/bool true)))
  (is (false? (ak/bool false)))
  (is (= 42 (a/value (-> 42 (ak/as :i32)))))
  (is (= 42 (a/value (ak/as 42 ak/i32))))
  (is (= 42 (a/value (.invoke ^clojure.lang.IFn ak/i32 42))))
  (let [before (count @@#'jvm/prepared-coercions)]
    (is (= 99 (a/value (ak/i32 99))))
    (is (= before (count @@#'jvm/prepared-coercions))))
  (doseq [[constructor input] [[ak/i8 128] [ak/u8 -1] [ak/u8 256]
                               [ak/i32 2147483648] [ak/i32 1.5]
                               [ak/bool 1]]]
    (is (thrown? clojure.lang.ExceptionInfo (constructor input)))))

(deftest complex-type-constructors-own-native-values
  (with-open [array (ak/as [1 2 3] [:array 3 :u8])
              slice (ak/as "héllo" [:slice-const :u8])
              optional (ak/as 17 [:optional :i32])
              absent (ak/as nil [:optional :i32])
              nested (ak/as [[1 2] [3 4]] [:array 2 [:array 2 ak/u16]])
              wide (ak/as (bigint "340282366920938463463374607431768211455") :u128)
              narrow (ak/u4 15)
              zero (ak/i0 0)
              result (ak/as {:ok 42} [:error-union :anyerror :i32])]
    (is (value/zig-value? array))
    (is (= [1 2 3] (a/value array)))
    (is (= [104 195 169 108 108 111] (a/value slice)))
    (is (= 17 (a/value optional)))
    (is (nil? (a/value absent)))
    (is (= [[1 2] [3 4]] (a/value nested)))
    (is (= (bigint "340282366920938463463374607431768211455") (a/value wide)))
    (is (= 15 (a/value narrow)))
    (is (= 0 (a/value zero)))
    (is (= {:ok 42} (a/value result)))
    (is (identical? array (ak/as array [:array 3 :u8]))))
  (is (thrown? Exception (ak/as [1 2] [:array 3 :u8])))
  (is (thrown? Exception (ak/u4 16))))

(deftest native-coercion-views-retain-their-source
  (with-open [source (ak/as [1 2 3] [:slice :u8])
              view (ak/as source [:slice-const :u8])]
    (is (= [1 2 3] (a/value view)))
    (is (identical? source (first (:owners (value/realize! view)))))))

(deftest constructors-in-native-functions
  (let [fixture (create-ns 'aguafria.coercion-fixture)]
    (binding [*ns* fixture]
      (refer 'clojure.core)
      (alias 'ak 'aguafria.keyword)
      (alias 'a 'aguafria.zig)
      (eval '(a/defstruct Foo [[:a {:default 1234} :i32] [:b :i32]]))
      (eval '(a/defn add :i32 []
               (let [one-plus-one (ak/i32 (+ 1 1))]
                 (-> one-plus-one (ak/as :i32)))))
      (eval '(a/defn quotient :f32 [] (ak/f32 (/ 7.0 3.0))))
      (eval '(a/defn input-type-name [:slice-const :u8]
               [[input :anytype]]
               (ak/typeName (ak/TypeOf input))))
      (eval '(a/defn sum :u32 [[values [:slice-const :u16]]]
               (+ (a/index values 0) (a/index values 1))))
      (eval '(a/defn first-field :i32 [[value Foo]] (a/field value :a))))
    (is (= 2 (a/value ((ns-resolve fixture 'add)))))
    (is (= (float (/ 7.0 3.0)) (float (a/value ((ns-resolve fixture 'quotient))))))
    (is (= "i32" (a/value ((ns-resolve fixture 'input-type-name) (ak/i32 2)))))
    (is (= "f32" (a/value ((ns-resolve fixture 'input-type-name) (ak/f32 2.5)))))
    (with-open [slice (ak/as [20 22] [:slice-const :u16])
                foo (ak/as {:b 5} (var-get (ns-resolve fixture 'Foo)))]
      (is (= 42 (a/value ((ns-resolve fixture 'sum) slice))))
      (is (= {:a 1234 :b 5} (a/value foo)))
      (is (= 1234 (a/value ((ns-resolve fixture 'first-field) foo)))))))
