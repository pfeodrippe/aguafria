(ns learn.example.test-error-union
  (:require [aguafria.keyword :as k]
            [aguafria.std.builtin.Type :as type-info]
            [aguafria.std.builtin.Type.ErrorUnion :as error-union-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest error-union-test
  (let [result (k/var k/undefined [:error-union :anyerror :i32])]
    ;; Both a payload and an error coerce into their shared error-union type.
    (k/= result 1234)
    (k/= result (az/error-value :SomeError))
    (try (k/comptime
          (testing/expectEqual :i32
                               (-> (k/typeInfo (k/TypeOf result))
                                   type-info/-error_union
                                   error-union-info/-payload))))
    (try (k/comptime
          (testing/expectEqual :anyerror
                               (-> (k/typeInfo (k/TypeOf result))
                                   type-info/-error_union
                                   error-union-info/-error_set))))))

(comment
  (error-union-test))
