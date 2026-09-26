(ns learn.example.test-overaligned-packed-struct
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct S {:layout :packed}
              [[:a :u32] [:b :u32]])

(az/deftest overaligned-pointer-to-packed-struct
  (let [foo (k/var (S {:a 1 :b 2}) nil {:zig/align 4})
        ptr (k/as (k/& foo) [:pointer {:align 4, :size :one} S])
        ptr-to-b (k/& (:b ptr))]
    (try (testing/expectEqual 2 @ptr-to-b))))

(comment
  (overaligned-pointer-to-packed-struct))
