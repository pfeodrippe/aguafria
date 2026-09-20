(ns learn.example.test-defining-variadic-function
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as ak]
            [aguafria.std.Target.Cpu :as cpu]
            [aguafria.std.Target.Os :as os]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- add :c_int
  {:zig/qualifiers "callconv(.c)"}
  [[count :c_int] [... {:zig/variadic true} _]]
  (let [arguments (ak/var (ak/cVaStart))]
    (ak/defer (ak/cVaEnd (& arguments)))
    (let [index (ak/var 0 :usize)
          sum (ak/var 0 :c_int)]
      (az/while-loop {:continue (az/assign-expr "+=" index 1)}
        (< index count)
        (ak/+= sum (ak/cVaArg (& arguments) :c_int)))
      sum)))

(az/deftest defining-variadic-function-test
  (let [architecture (cpu/-arch builtin/cpu)
        operating-system (os/-tag builtin/os)]
    (when (and (ak/== architecture :.aarch64) (ak/!= operating-system :.macos))
      ;; https://github.com/ziglang/zig/issues/14096
      (ak/return (az/error-value :SkipZigTest)))
    (when (and (ak/== architecture :.x86_64) (ak/== operating-system :.windows))
      ;; https://github.com/ziglang/zig/issues/16961
      (ak/return (az/error-value :SkipZigTest)))
    (when (ak/== architecture :.s390x)
      ;; https://github.com/ziglang/zig/issues/21350#issuecomment-3543006475
      (ak/return (az/error-value :SkipZigTest))))

  (try (testing/expectEqual (ak/as 0 :c_int) (add 0)))
  (try (testing/expectEqual (ak/as 1 :c_int) (add 1 (ak/as 1 :c_int))))
  (try (testing/expectEqual (ak/as 3 :c_int)
                            (add 2 (ak/as 1 :c_int) (ak/as 2 :c_int)))))

(comment
  (defining-variadic-function-test))
