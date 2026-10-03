(ns learn.example.test-noreturn-from-exit
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defextern ExitProcess :noreturn
  {:zig/prefix "extern \"kernel32\"" :callconv :.winapi}
  [[exit-code :c_uint]])

(az/defn- bar [:error-union :anyerror :u32] []
  1234)

(az/deftest foo
  (let [value (catch (bar) (ExitProcess 1))]
    (try (testing/expectEqual 1234 value))))

(comment
  (foo))
