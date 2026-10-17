(ns learn.example.test-aligned-struct-fields
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest aligned-struct-fields
  (let [S (a/struct
           [[:a {:align 2} :u32]
            [:b {:align 64} :u32]])
        foo (k/var (S {:a 1 :b 2}))]
    (k/try (testing/expectEqual 64 (alignOf S)))
    (k/try (testing/expectEqual (a/type [:* {:align 2} :u32])
                                (TypeOf (k/& (:a foo)))))
    (k/try (testing/expectEqual (a/type [:* {:align 64} :u32])
                                (TypeOf (k/& (:b foo)))))))

(comment
  (aligned-struct-fields))
