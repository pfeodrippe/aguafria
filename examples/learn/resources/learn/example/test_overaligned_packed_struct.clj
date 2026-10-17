(ns learn.example.test-overaligned-packed-struct
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defstruct S {:layout :packed}
             [[:a :u32] [:b :u32]])

(a/deftest overaligned-pointer-to-packed-struct
  (let [foo (k/var (S {:a 1 :b 2}) nil {:align 4})
        ptr (as (k/& foo) [:* {:align 4} S])
        ptr-to-b (k/& (:b ptr))]
    (k/try (testing/expectEqual 2 @ptr-to-b))))

(comment
  (overaligned-pointer-to-packed-struct))
