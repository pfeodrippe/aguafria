(ns learn.example.test-variadic-function
  (:require [aguafria.keyword :as k]
            [aguafria.std.lang.Type :as type-info]
            [aguafria.std.lang.Type.Fn :as fn-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defextern printf :c_int
  {:zig/prefix "pub extern \"c\""}
  [[format [:sentinel-const :u8 0]] [... {:zig/variadic true} _]])

(a/deftest variadic-function
  (try (testing/expectEqual 14 (printf "Hello, world!\n")))
  (try (testing/expect
        (-> (k/typeInfo (k/TypeOf printf)) type-info/-fn fn-info/-attrs :varargs))))

(comment
  (variadic-function))
