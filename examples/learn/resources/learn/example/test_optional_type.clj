(ns learn.example.test-optional-type
  (:require [aguafria.keyword :as k]
            [aguafria.std.builtin.Type :as type-info]
            [aguafria.std.builtin.Type.Optional :as optional-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest optional-type-test
  ;; Declare an optional and coerce from null.
  (let [optional-number (k/var nil [:optional :i32])]
    ;; Coerce from the optional's child type.
    (k/= optional-number 1234)

    ;; Use compile-time reflection to access the child type of the optional.
    (try (k/comptime
          (testing/expectEqual
           :i32
           (-> (k/typeInfo (k/TypeOf optional-number))
               type-info/-optional
               optional-info/-child))))))

(comment
  (optional-type-test))
