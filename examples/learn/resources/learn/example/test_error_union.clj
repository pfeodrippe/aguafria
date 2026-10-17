(ns learn.example.test-error-union
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.lang.Type :as type-info]
            [aguafria.std.lang.Type.ErrorUnion :as error-union-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest error-union
  (let [foo (k/var undefined [:error-union :anyerror :i32])]
    ;; Coerce from child type of an error union:
    (= foo 1234)
    ;; Coerce from an error set:
    (= foo (a/error-value :SomeError))
    ;; Use compile-time reflection to access the payload type of an error union:
    (k/try (comptime
            (testing/expectEqual :i32
                                 (-> (typeInfo (TypeOf foo))
                                     type-info/-error_union
                                     error-union-info/-payload))))
    ;; Use compile-time reflection to access the error set type of an error union:
    (k/try (comptime
            (testing/expectEqual :anyerror
                                 (-> (typeInfo (TypeOf foo))
                                     type-info/-error_union
                                     error-union-info/-error_set))))))

(comment
  (error-union))
