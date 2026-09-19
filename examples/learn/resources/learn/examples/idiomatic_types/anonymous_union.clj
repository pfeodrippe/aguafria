(ns learn.examples.idiomatic-types.anonymous-union
  (:require aguafria.std
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst NumericValue
  (az/container {:kind :union}
    (az/field-decl :int :i32)
    (az/field-decl :float :f64)))

(az/defn make-number NumericValue []
  {:float 12.34})

(az/deftest anonymous-union-literal-test
  (let [^{:zig/type NumericValue} integer {:int 42}
        floating (make-number)]
    (try (testing/expectEqual 42 (az/field integer :int)))
    (try (testing/expectEqual 12.34 (az/field floating :float)))))
