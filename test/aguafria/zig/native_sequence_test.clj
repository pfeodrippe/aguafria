(ns aguafria.zig.native-sequence-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.value :as value]
            [clojure.test :refer [deftest is]]))

(deftest array-destructuring-retains-types-and-storage-owners
  (with-open [array (a/array [17 29] :u32)]
    (let [[x y] array
          elements (vec (seq array))]
      (is (every? value/zig-value? [x y]))
      (is (= [:u32 :u32] (mapv value/qualified-type elements)))
      (is (= [17 29] (mapv a/value elements)))
      (is (every? (fn [element]
                    (some #(identical? array %) (:owners (value/realize! element))))
                  elements))
      (is (= :missing (nth array 2 :missing)))
      (is (= :missing (nth array -1 :missing)))
      (is (thrown? IndexOutOfBoundsException (nth array 2))))))

(deftest mutable-array-sequences-observe-native-storage
  (with-open [array (k/var (a/array [3 5] :i32))]
    (let [[first-element] array]
      (is (value/zig-value? first-element))
      (is (= :i32 (value/qualified-type first-element)))
      (k/= (a/index array 0) 41)
      (is (= 41 (a/value first-element)))
      (is (= [41 5] (mapv a/value (seq array)))))))

(deftest nested-and-sentinel-arrays-preserve-element-shapes
  (with-open [nested (a/array [[1 2] [3 4]] [:array 2 :u8])
              sentinel (k/as [7 9] [:array 2 {:sentinel 0} :u8])]
    (let [rows (vec (seq nested))
          bytes (vec (seq sentinel))]
      (is (= [[:array 2 :u8] [:array 2 :u8]] (mapv value/qualified-type rows)))
      (is (= [[1 2] [3 4]] (mapv #(mapv a/value (seq %)) rows)))
      (is (= [:u8 :u8] (mapv value/qualified-type bytes)))
      (is (= [7 9] (mapv a/value bytes)))
      (is (= :missing (nth sentinel 2 :missing))))))
