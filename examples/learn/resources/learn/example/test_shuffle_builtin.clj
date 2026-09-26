(ns learn.example.test-shuffle-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest vector-shuffle
  (let [a (az/init [\o \l \h \e \r \z \w] [:vector 7 :u8])
        b (az/init [\w \d \! \x] [:vector 4 :u8])
        ;; To shuffle within a single vector, pass undefined as the second argument.
        ;; Notice that we can re-order, duplicate, or omit elements of the input vector
        mask1 (az/init [2 3 1 1 0] [:vector 5 :i32])
        res1 (k/shuffle (az/type :u8) a k/undefined mask1)]
    (try (testing/expectEqualStrings
          "hello" (k/& (k/as res1 (az/type [:array 5 :u8])))))
    ;; Combining two vectors
    (let [mask2 (az/init [-1 0 4 1 -2 -3] [:vector 6 :i32])
          res2 (k/shuffle (az/type :u8) a b mask2)]
      (try (testing/expectEqualStrings
            "world!" (k/& (k/as res2 (az/type [:array 6 :u8]))))))))

(comment
  (vector-shuffle))
