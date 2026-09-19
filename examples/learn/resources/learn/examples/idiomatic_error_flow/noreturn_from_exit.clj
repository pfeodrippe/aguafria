(ns learn.examples.idiomatic-error-flow.noreturn-from-exit
  "Converted from test_noreturn_from_exit.zig"
  (:require [aguafria.std.builtin :as builtin-types]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

;; This lesson intentionally targets x86_64-windows, just like the original.
(az/defimport target "builtin" [[native-arch "cpu.arch"]])

(az/defconst WINAPI builtin-types/CallingConvention
  (if (== target/native-arch :.x86)
    (az/object [[:x86_stdcall (az/object [])]])
    :.c))

(az/defextern ExitProcess
  {:zig/prefix "extern \"kernel32\"" :zig/qualifiers "callconv(WINAPI)"}
  :- :noreturn [[exit-code :c_uint]])

(az/defn- successful-number [:error-union :anyerror :u32] []
  1234)

(az/deftest noreturn-fallback-test
  ;; A noreturn handler coerces to any payload type because it never returns.
  (let [number (catch (successful-number) (ExitProcess 1))]
    (try (testing/expectEqual 1234 number))))
