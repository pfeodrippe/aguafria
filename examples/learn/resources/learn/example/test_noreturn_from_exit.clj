(ns learn.example.test-noreturn-from-exit
  (:require [aguafria.keyword :as k]
            [aguafria.std.builtin :as builtin-types]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defimport target "builtin" [[native-arch "cpu.arch"]])

(az/defconst WINAPI builtin-types/CallingConvention
  (if (k/== target/native-arch :.x86)
    (az/object [[:x86_stdcall (az/object [])]])
    :.c))

(az/defextern ExitProcess :noreturn
  {:zig/prefix "extern \"kernel32\"" :zig/qualifiers "callconv(WINAPI)"}
  [[exit-code :c_uint]])

(az/defn- bar [:error-union :anyerror :u32] []
  1234)

(az/deftest foo
  (let [value (catch (bar) (ExitProcess 1))]
    (try (testing/expectEqual 1234 value))))

(comment
  (foo))
