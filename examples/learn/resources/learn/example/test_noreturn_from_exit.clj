(ns learn.example.test-noreturn-from-exit
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defextern ExitProcess :noreturn
  {:zig/prefix "extern \"kernel32\"" :callconv :winapi}
  [[exit-code :c_uint]])

(a/defn- bar [:error-union :anyerror :u32] []
  1234)

(a/deftest foo
  (let [value (k/catch (bar) (ExitProcess 1))]
    (k/try (testing/expectEqual 1234 value))))

(comment
  (foo))
