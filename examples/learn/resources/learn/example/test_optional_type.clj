(ns learn.example.test-optional-type
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.lang.Type :as type-info]
            [aguafria.std.lang.Type.Optional :as optional-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest optional-type
  ;; Declare an optional and coerce from null:
  (let [foo (k/var nil [:optional :i32])]
    ;; Coerce from child type of an optional
    (= foo 1234)

    ;; Use compile-time reflection to access the child type of the optional:
    (k/try (comptime
            (testing/expectEqual
             :i32
             (-> (typeInfo (TypeOf foo))
                 type-info/-optional
                 optional-info/-child))))))

(comment
  (optional-type))
