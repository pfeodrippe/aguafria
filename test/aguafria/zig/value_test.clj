(ns aguafria.zig.value-test
  (:require [aguafria.keyword :as ak]
            [aguafria.std :as std]
            [aguafria.zig :as a]
            [aguafria.zig.value :as value]
            [clojure.pprint :as pprint]
            [clojure.test :refer [deftest is]]))

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
      (let [items (if (= type :u8) "" [])]
        (is (= {:items items :capacity 0} @native))
        (is (= (str "#aguafria.zig.value.ZigValue["
                    (pr-str {:items items :capacity 0}) "]")
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
