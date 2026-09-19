(ns learn.example.test-packed-union-equality
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest packed-union-equality-test
  (let [Nibble (az/container {:kind :union :layout :packed}
                 (az/field-decl :a :u4)
                 (az/field-decl :b :i4))
        ^{:zig/type Nibble} unsigned {:a 3}
        ^{:zig/type Nibble} signed {:b 3}]
    (try (testing/expectEqual unsigned signed))))
