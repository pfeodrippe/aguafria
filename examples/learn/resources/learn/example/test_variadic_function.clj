(ns learn.example.test-variadic-function
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.lang.Type :as type-info]
            [aguafria.std.lang.Type.Fn :as fn-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defextern printf :c_int
  {:zig/prefix "pub extern \"c\""}
  [[format [:sentinel-const :u8 0]] [... {:zig/variadic true} _]])

(a/deftest variadic-function
  (k/try (testing/expectEqual 14 (printf "Hello, world!\n")))
  (k/try (testing/expect
          (-> (typeInfo (TypeOf printf)) type-info/-fn fn-info/-attrs :varargs))))

(comment
  (variadic-function))
