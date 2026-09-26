(ns learn.example.test-overaligned-packed-struct
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct S {:layout :packed}
  [[:a :u32] [:b :u32]])

(az/deftest overaligned-pointer-to-packed-struct-test
  (let [words (k/var (S {:a 1 :b 2}) nil {:zig/align 4})
        pointer (k/as (k/& words) [:pointer {:align 4, :size :one} S])
        second-word (k/& (:b pointer))]
    (try (testing/expectEqual 2 @second-word))))

(comment
  (overaligned-pointer-to-packed-struct-test))
