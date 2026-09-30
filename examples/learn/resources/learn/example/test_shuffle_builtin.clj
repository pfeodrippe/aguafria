(ns learn.example.test-shuffle-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest vector-shuffle
  (let [a (az/vector [\o \l \h \e \r \z \w] :u8)
        b (az/vector [\w \d \! \x] :u8)
        ;; To shuffle within a single vector, pass undefined as the second argument.
        ;; Notice that we can re-order, duplicate, or omit elements of the input vector
        mask1 (az/vector [2 3 1 1 0] :i32)
        res1 (k/shuffle :u8 a k/undefined mask1)]
    (try (testing/expectEqualStrings
          "hello" (k/& (k/as res1 [:array 5 :u8]))))
    ;; Combining two vectors
    (let [mask2 (az/vector [-1 0 4 1 -2 -3] :i32)
          res2 (k/shuffle :u8 a b mask2)]
      (try (testing/expectEqualStrings
            "world!" (k/& (k/as res2 [:array 6 :u8])))))))

(comment
  (vector-shuffle))
