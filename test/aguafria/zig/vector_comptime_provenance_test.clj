(ns aguafria.zig.vector-comptime-provenance-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.jvm :as jvm]
            [clojure.test :refer [deftest is]]))

(deftest shuffle-retains-compiler-constructed-integer-literals
  (with-open [lanes (a/vector [11 22] :u8)
              mask (a/vector [1 0 1] :i32)
              result (k/shuffle :u8 lanes k/undefined mask)]
    (is (= [22 11 22] (mapv #(a/value (a/get result %)) (range 3))))
    (is (some? (#'jvm/coercion-expression mask)))
    ;; Constness alone is insufficient. A mutable copy cannot acquire the
    ;; original literal proof, even when its current bytes happen to match.
    (with-open [mutable-mask (k/var mask)]
      (is (nil? (#'jvm/coercion-expression mutable-mask)))
      (is (thrown? Exception (k/shuffle :u8 lanes k/undefined mutable-mask))))))

(deftest native-lanes-are-not-read-back-as-comptime-mask-data
  (with-open [lane (k/var 1 :i32)
              lanes (a/vector [11 22] :u8)
              mask (a/vector [lane 0] :i32)]
    (is (not (jvm/literal-integer-vector? [lane 0] [:vector 2 :i32])))
    (is (nil? (#'jvm/coercion-expression mask)))
    (is (thrown? Exception (k/shuffle :u8 lanes k/undefined mask)))))

(deftest literal-vector-proof-is-shape-and-element-type-specific
  (is (jvm/literal-integer-vector? [\a \b] [:vector 2 :u8]))
  (is (not (jvm/literal-integer-vector? [1 2] [:array 2 :i32])))
  (is (not (jvm/literal-integer-vector? [1 2] [:vector 3 :i32])))
  (is (not (jvm/literal-integer-vector? [1.0 2.0] [:vector 2 :f32]))))
