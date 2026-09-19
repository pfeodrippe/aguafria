(ns learn.example.test-overaligned-packed-struct
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct PackedWords {:layout :packed}
  [[:a :u32] [:b :u32]])

(az/deftest overaligned-pointer-to-packed-struct-test
  (let [^{:var PackedWords :zig/align 4} words {:a 1 :b 2}
        ^{:zig/type [:pointer {:size :one :align 4} PackedWords]} pointer (& words)
        second-word (& (az/field pointer :b))]
    (try (testing/expectEqual 2 @second-word))))
