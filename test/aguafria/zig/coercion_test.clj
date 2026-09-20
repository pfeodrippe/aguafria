(ns aguafria.zig.coercion-test
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]
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

(deftest primitive-constructors-return-real-jvm-values
  (is (= 2 (ak/i32 (+ 1 1))))
  (is (instance? Integer (ak/i32 2)))
  (is (= (float (/ 7.0 3.0)) (ak/f32 (/ 7.0 3.0))))
  (is (instance? Float (ak/f32 2.5)))
  (is (= 2.5 (ak/f64 2.5)))
  (is (= 42 (ak/as 42 :c_int)))
  (is (= 42 (ak/c_int 42)))
  (is (nil? (ak/as nil :void)))
  (is (= 42 (ak/comptime_int 42)))
  (is (= 2.5 (ak/comptime_float 2.5)))
  (is (true? (ak/bool true)))
  (is (false? (ak/bool false)))
  (is (= 42 (-> 42 (ak/as :i32))))
  (is (= 42 (ak/as 42 ak/i32)))
  (is (= 42 (.invoke ^clojure.lang.IFn ak/i32 42)))
  (let [before (count @@#'jvm/prepared-coercions)]
    (is (= 99 (ak/i32 99)))
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
    (is (= [1 2 3] (az/value array)))
    (is (= [104 195 169 108 108 111] (az/value slice)))
    (is (= 17 (az/value optional)))
    (is (nil? (az/value absent)))
    (is (= [[1 2] [3 4]] (az/value nested)))
    (is (= (bigint "340282366920938463463374607431768211455") (az/value wide)))
    (is (= 15 (az/value narrow)))
    (is (= 0 (az/value zero)))
    (is (= {:ok 42} (az/value result)))
    (is (identical? array (ak/as array [:array 3 :u8]))))
  (is (thrown? Exception (ak/as [1 2] [:array 3 :u8])))
  (is (thrown? Exception (ak/u4 16))))

(deftest native-coercion-views-retain-their-source
  (with-open [source (ak/as [1 2 3] [:slice :u8])
              view (ak/as source [:slice-const :u8])]
    (is (= [1 2 3] (az/value view)))
    (is (identical? source (first (:owners (value/realize! view)))))))

(deftest constructors-in-native-functions
  (let [fixture (create-ns 'aguafria.coercion-fixture)]
    (binding [*ns* fixture]
      (refer 'clojure.core)
      (alias 'ak 'aguafria.keyword)
      (alias 'az 'aguafria.zig)
      (eval '(az/defstruct Foo [[:a {:default 1234} :i32] [:b :i32]]))
      (eval '(az/defn add :i32 []
               (let [one-plus-one (ak/i32 (+ 1 1))]
                 (-> one-plus-one (ak/as :i32)))))
      (eval '(az/defn quotient :f32 [] (ak/f32 (/ 7.0 3.0))))
      (eval '(az/defn input-type-name [:slice-const :u8]
               [[input :anytype]]
               (ak/typeName (ak/TypeOf input))))
      (eval '(az/defn sum :u32 [[values [:slice-const :u16]]]
               (+ (az/index values 0) (az/index values 1))))
      (eval '(az/defn first-field :i32 [[value Foo]] (az/field value :a))))
    (is (= 2 ((ns-resolve fixture 'add))))
    (is (= (float (/ 7.0 3.0)) ((ns-resolve fixture 'quotient))))
    (is (= "i32" ((ns-resolve fixture 'input-type-name) (ak/i32 2))))
    (is (= "f32" ((ns-resolve fixture 'input-type-name) (ak/f32 2.5))))
    (with-open [slice (ak/as [20 22] [:slice-const :u16])
                foo (ak/as {:b 5} (var-get (ns-resolve fixture 'Foo)))]
      (is (= 42 ((ns-resolve fixture 'sum) slice)))
      (is (= {:a 1234 :b 5} (az/value foo)))
      (is (= 1234 ((ns-resolve fixture 'first-field) foo))))))
