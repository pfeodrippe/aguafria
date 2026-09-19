(ns learn.examples.idiomatic-values.optional-type
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest optional-type-test
  ;; Declare an optional and coerce from null.
  (let [^{:var [:optional :i32]} optional-number nil]
    ;; Coerce from the optional's child type.
    (set! optional-number 1234)

    ;; Use compile-time reflection to access the child type of the optional.
    (try (ak/comptime
           (testing/expectEqual
             :i32
             (az/field (az/field (ak/typeInfo (ak/TypeOf optional-number)) :optional)
                       :child))))))
