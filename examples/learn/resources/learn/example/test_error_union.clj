(ns learn.example.test-error-union
  (:require [aguafria.keyword :as k]
            [aguafria.std.builtin.Type :as type-info]
            [aguafria.std.builtin.Type.ErrorUnion :as error-union-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest error-union
  (let [foo (k/var k/undefined [:error-union :anyerror :i32])]
    ;; Coerce from child type of an error union:
    (k/= foo 1234)
    ;; Coerce from an error set:
    (k/= foo (az/error-value :SomeError))
    ;; Use compile-time reflection to access the payload type of an error union:
    (try (k/comptime
          (testing/expectEqual :i32
                               (-> (k/typeInfo (k/TypeOf foo))
                                   type-info/-error_union
                                   error-union-info/-payload))))
    ;; Use compile-time reflection to access the error set type of an error union:
    (try (k/comptime
          (testing/expectEqual :anyerror
                               (-> (k/typeInfo (k/TypeOf foo))
                                   type-info/-error_union
                                   error-union-info/-error_set))))))

(comment
  (error-union))
