(ns learn.example.test-packed-union-equality
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest packed-union-equality-test
  (let [Nibble (az/union {:layout :packed}
                 [[:a :u4]
                  [:b :i4]])
        ^{:zig/type Nibble} unsigned {:a 3}
        ^{:zig/type Nibble} signed {:b 3}]
    (try (testing/expectEqual unsigned signed))))

(comment
  (packed-union-equality-test))
