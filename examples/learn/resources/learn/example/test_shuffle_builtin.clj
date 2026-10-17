(ns learn.example.test-shuffle-builtin
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest vector-shuffle
  (let [a (a/vector [\o \l \h \e \r \z \w] :u8)
        b (a/vector [\w \d \! \x] :u8)
        ;; To shuffle within a single vector, pass undefined as the second argument.
        ;; Notice that we can re-order, duplicate, or omit elements of the input vector
        mask1 (a/vector [2 3 1 1 0] :i32)
        res1 (shuffle :u8 a undefined mask1)]
    (k/try (testing/expectEqualStrings
            "hello" (k/& (as res1 [:array 5 :u8]))))
    ;; Combining two vectors
    (let [mask2 (a/vector [-1 0 4 1 -2 -3] :i32)
          res2 (shuffle :u8 a b mask2)]
      (k/try (testing/expectEqualStrings
              "world!" (k/& (as res2 [:array 6 :u8])))))))

(comment
  (vector-shuffle))
