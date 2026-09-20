(ns learn.example.test-packed-struct-equality
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest packed-struct-equality-test
  (let [Nibbles (az/container {:kind :struct :layout :packed}
                  (az/field-decl :a :u4)
                  (az/field-decl :b :u4))
        ^{:zig/type Nibbles} forward {:a 1 :b 2}
        ^{:zig/type Nibbles} reversed {:b 2 :a 1}]
    ;; Literal field order does not affect the packed representation.
    (try (testing/expectEqual forward reversed))))

(comment
  (packed-struct-equality-test))
