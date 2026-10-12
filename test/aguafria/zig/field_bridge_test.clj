(ns aguafria.zig.field-bridge-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.value :as value]
            [clojure.test :refer [deftest is]]))

(a/defstruct Point [[:x :i32] [:y :i32]])
(a/defconst Pair (a/struct [(a/tuple-field-decl :u8) (a/tuple-field-decl :u8)]))
(a/defstruct Bits {:layout :packed} [[:a :u3] [:b :u3] [:c :u2]])
(a/defstruct AlignedBits {:layout :packed} [[:a :u32] [:b :u32]])
(a/defn echo-bits :u3 [[input :u3]] input)
(a/defunion UndefinedPayload [[:float :f32] [:int :u32]])
(a/defstruct NestedUndefined [[:count :u32] [:payload UndefinedPayload]])

(deftest undefined-aggregate-members-stay-compiler-syntax-until-initialized
  (with-open [payload (k/var (UndefinedPayload {:float k/undefined}))
              nested (k/var (NestedUndefined {:count 3 :payload {:int k/undefined}}))]
    ;; Do not inspect an undefined field. Write it before observing its value.
    (k/= (:float payload) 2.5)
    (is (= 2.5 (a/value (:float payload))))
    (k/= (:int (:payload nested)) 17)
    (is (= 17 (a/value (:int (:payload nested)))))
    (is (= 3 (a/value (:count nested)))))
  (is (jvm/native-construction-argument? {:nested [k/undefined]}))
  (is (not (jvm/native-construction-argument? {:count 3}))))

(deftest builtin-field-keeps-mutable-aliases-and-constness
  (with-open [point (k/var (Point {:x 1 :y 2}))
              constant (Point {:x 3 :y 4})]
    (let [x (k/field point "x")]
      (is (= :var (:kind (value/info x))))
      (k/= x 17)
      (is (= 17 (a/value (:x point))))
      (k/= (:x point) 23)
      (is (= 23 (a/value x))))
    (is (thrown? clojure.lang.ExceptionInfo (k/= (k/field constant "x") 99)))))

(deftest packed-field-views-preserve-address-bit-range-and-live-mutation
  (with-open [bits (k/var (Bits {:a 1 :b 2 :c 3}))]
    (let [field (:b bits)
          pointer (k/& field)]
      (is (= [:* {:align 1 :bit-start 3 :bit-end 1} :u3] (value/storage-type pointer)))
      (is (= (a/value (k/intFromPtr (k/& (:a bits))))
             (a/value (k/intFromPtr pointer))))
      (is (= 2 (a/value @pointer)))
      (k/= field 5)
      (is (= 5 (a/value @pointer)))
      (is (= 5 (a/value (echo-bits field))))
      (value/set-value! field 6)
      (is (= {:a 1 :b 6 :c 3} (a/value bits)))
      (is (= 6 (a/value (echo-bits field))))
      (k/= (:b bits) 4)
      (is (= 4 (a/value field)))
      (is (thrown? Exception (k/as pointer [:* :u3])))))
  (with-open [constant (Bits {:a 1 :b 2 :c 3})]
    (is (thrown? clojure.lang.ExceptionInfo (k/= (:b constant) 5)))))

(deftest byte-aligned-packed-fields-can-still-carry-host-bit-ranges
  (with-open [bits (k/var (AlignedBits {:a 1 :b 2}) nil {:align 4})]
    (let [pointer (k/as (k/& bits) [:* {:align 4} AlignedBits])
          field (:b pointer)
          address (k/& field)]
      (is (= [:* {:align 4 :bit-start 32 :bit-end 8} :u32] (value/storage-type address)))
      (is (= 2 (a/value @address)))
      (k/= field 42)
      (is (= {:a 1 :b 42} (a/value bits)))
      (value/set-value! field 43)
      (is (= 43 (a/value @address))))))

(deftest native-empty-expressions-remain-void-not-null
  (let [empty-block (a/block)]
    (is (k/== :void (k/TypeOf empty-block)))
    (is (k/!= (k/TypeOf empty-block) (k/TypeOf nil)))
    (is (nil? (a/value empty-block)))))

(deftest explicit-tuple-construction-keeps-the-declared-type
  (with-open [pair (k/as [5 6] Pair)
              array (k/as pair [:array 2 :u8])]
    (is (= (jvm/constructor-type Pair)
           (jvm/constructor-type (value/qualified-type pair))))
    (is (= [5 6] (a/value array)))))

(deftest inferred-string-readers-prepare-the-utf8-pointer-without-refining-typed-slices
  (let [types (atom [])]
    (with-redefs-fn {#'jvm/constant-reader-plan (constantly {})
                     #'jvm/precompile-expression! (constantly nil)
                     #'jvm/precompile-inspection! (fn [_ type] (swap! types conj type))}
      #(do (jvm/precompile-constant-reader! {:module "aguafria.zig.field-bridge-test"
                                             :name 'inferred :value "hé"})
           (jvm/precompile-constant-reader! {:module "aguafria.zig.field-bridge-test"
                                             :name 'typed :type [:slice-const :u8] :value "hé"})))
    (is (= ['(aguafria.keyword/TypeOf aguafria.zig.field-bridge-test/inferred)
            [:*const [:array 3 {:sentinel 0} :u8]]
            [:slice-const :u8]] @types))))

(deftest field-metadata-serialization-does-not-depend-on-map-order
  (let [left [:* {:sentinel 0 :size :slice :const? true} :u8]
        right [:* {:size :slice :const? true :sentinel 0} :u8]]
    (is (= (#'jvm/field-view-plan left :len [:*const left])
           (#'jvm/field-view-plan right :len [:*const right])))
    (is (not= (#'jvm/type-source-data left)
              (#'jvm/type-source-data [:slice-const :u8])))))
