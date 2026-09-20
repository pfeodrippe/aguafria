(ns learn.example.test-error-union
  (:require [aguafria.keyword :as ak]
            [aguafria.std.builtin.Type :as type-info]
            [aguafria.std.builtin.Type.ErrorUnion :as error-union-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest error-union-test
  (let [result (ak/var ak/undefined [:error-union :anyerror :i32])]
    ;; Both a payload and an error coerce into their shared error-union type.
    (ak/= result 1234)
    (ak/= result (az/error-value :SomeError))
    (try (ak/comptime
          (testing/expectEqual :i32
                               (-> (ak/typeInfo (ak/TypeOf result))
                                   type-info/-error_union
                                   error-union-info/-payload))))
    (try (ak/comptime
          (testing/expectEqual :anyerror
                               (-> (ak/typeInfo (ak/TypeOf result))
                                   type-info/-error_union
                                   error-union-info/-error_set))))))

(comment
  (error-union-test))
