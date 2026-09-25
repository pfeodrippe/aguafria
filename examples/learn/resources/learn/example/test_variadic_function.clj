(ns learn.example.test-variadic-function
  (:require [aguafria.keyword :as k]
            [aguafria.std.builtin.Type :as type-info]
            [aguafria.std.builtin.Type.Fn :as fn-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defextern printf :c_int
  {:zig/prefix "pub extern \"c\""}
  [[format [:sentinel-const :u8 0]] [... {:zig/variadic true} _]])

(az/deftest variadic-function-test
  (try (testing/expectEqual 14 (printf "Hello, world!\n")))
  (try (testing/expect
        (-> (k/typeInfo (k/TypeOf printf)) type-info/-fn fn-info/-is_var_args))))

(comment
  (variadic-function-test))
