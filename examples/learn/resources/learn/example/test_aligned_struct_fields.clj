(ns learn.example.test-aligned-struct-fields
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest aligned-struct-fields
  (let [S (a/struct
           [[:a {:align 2} :u32]
            [:b {:align 64} :u32]])
        foo (k/var (S {:a 1 :b 2}))]
    (try (testing/expectEqual 64 (k/alignOf S)))
    (try (testing/expectEqual (a/type [:* {:align 2} :u32])
                              (k/TypeOf (k/& (:a foo)))))
    (try (testing/expectEqual (a/type [:* {:align 64} :u32])
                              (k/TypeOf (k/& (:b foo)))))))

(comment
  (aligned-struct-fields))
