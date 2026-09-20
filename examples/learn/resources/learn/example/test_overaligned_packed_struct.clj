(ns learn.example.test-overaligned-packed-struct
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct S {:layout :packed}
  [[:a :u32] [:b :u32]])

(az/deftest overaligned-pointer-to-packed-struct-test
  (let [words (ak/var (S {:a 1 :b 2}) nil {:zig/align 4})
        pointer (ak/as (& words) [:pointer {:align 4, :size :one} S])
        second-word (& (az/field pointer :b))]
    (try (testing/expectEqual 2 @second-word))))

(comment
  (overaligned-pointer-to-packed-struct-test))
