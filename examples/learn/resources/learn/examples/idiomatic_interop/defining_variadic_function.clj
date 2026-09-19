(ns learn.examples.idiomatic-interop.defining-variadic-function
  "Converted from test_defining_variadic_function.zig"
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst builtin (ak/import "builtin"))

(az/defn- add :c_int
  {:zig/qualifiers "callconv(.c)"}
  [[count :c_int] [... {:zig/variadic true} _]]
  (let [^:var arguments (ak/cVaStart)]
    (ak/defer (ak/cVaEnd (& arguments)))
    (let [^{:var :usize} index 0
          ^{:var :c_int} sum 0]
      (az/while-loop {:continue (az/assign-expr "+=" index 1)}
        (< index count)
        (ak/+= sum (ak/cVaArg (& arguments) :c_int)))
      sum)))

(az/deftest defining-variadic-function-test
  (let [architecture (az/field (az/field builtin :cpu) :arch)
        operating-system (az/field (az/field builtin :os) :tag)]
    (when (and (== architecture :.aarch64) (!= operating-system :.macos))
      ;; https://github.com/ziglang/zig/issues/14096
      (ak/return (az/error-value :SkipZigTest)))
    (when (and (== architecture :.x86_64) (== operating-system :.windows))
      ;; https://github.com/ziglang/zig/issues/16961
      (ak/return (az/error-value :SkipZigTest)))
    (when (== architecture :.s390x)
      ;; https://github.com/ziglang/zig/issues/21350#issuecomment-3543006475
      (ak/return (az/error-value :SkipZigTest))))

  (try (testing/expectEqual (ak/as :c_int 0) (add 0)))
  (try (testing/expectEqual (ak/as :c_int 1) (add 1 (ak/as :c_int 1))))
  (try (testing/expectEqual (ak/as :c_int 3)
                            (add 2 (ak/as :c_int 1) (ak/as :c_int 2)))))
