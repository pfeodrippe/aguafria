(ns learn.example.test-optional-type
  (:require [aguafria.keyword :as ak]
            [aguafria.std.builtin.Type :as type-info]
            [aguafria.std.builtin.Type.Optional :as optional-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest optional-type-test
  ;; Declare an optional and coerce from null.
  (let [optional-number (ak/var nil [:optional :i32])]
    ;; Coerce from the optional's child type.
    (ak/= optional-number 1234)

    ;; Use compile-time reflection to access the child type of the optional.
    (try (ak/comptime
          (testing/expectEqual
           :i32
           (-> (ak/typeInfo (ak/TypeOf optional-number))
               type-info/-optional
               optional-info/-child))))))

(comment
  (optional-type-test))
