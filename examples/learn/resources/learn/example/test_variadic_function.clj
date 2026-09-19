(ns learn.example.test-variadic-function
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defextern printf
  {:zig/prefix "pub extern \"c\""}
  :- :c_int
  [[format [:sentinel-const :u8 0]] [... {:zig/variadic true} _]])

(az/deftest variadic-function-test
  (try (testing/expectEqual 14 (printf "Hello, world!\n")))
  (try (testing/expect
         (az/field (az/field (ak/typeInfo (ak/TypeOf printf)) :fn) :is_var_args))))
