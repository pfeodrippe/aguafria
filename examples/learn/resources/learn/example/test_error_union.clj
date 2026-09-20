(ns learn.example.test-error-union
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest error-union-test
  (let [^:var result (ak/as ak/undefined [:error-union :anyerror :i32])]
    ;; Both a payload and an error coerce into their shared error-union type.
    (set! result 1234)
    (set! result (az/error-value :SomeError))
    (try (ak/comptime
          (testing/expectEqual :i32
                               (az/field (az/field (ak/typeInfo (ak/TypeOf result)) :error_union)
                                         :payload))))
    (try (ak/comptime
          (testing/expectEqual :anyerror
                               (az/field (az/field (ak/typeInfo (ak/TypeOf result)) :error_union)
                                         :error_set))))))

(comment
  (error-union-test))
