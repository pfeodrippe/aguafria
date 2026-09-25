(ns learn.example.test-defining-variadic-function
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k]
            [aguafria.std.Target.Cpu :as cpu]
            [aguafria.std.Target.Os :as os]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- add :c_int
  {:zig/qualifiers "callconv(.c)"}
  [[count :c_int] [... {:zig/variadic true} _]]
  (let [arguments (k/var (k/cVaStart))]
    (k/defer (k/cVaEnd (k/& arguments)))
    (let [index (k/var 0 :usize)
          sum (k/var 0 :c_int)]
      (az/while-loop {:continue (az/assign-expr "+=" index 1)}
        (k/< index count)
        (k/+= sum (k/cVaArg (k/& arguments) :c_int)))
      sum)))

(az/deftest defining-variadic-function-test
  (let [architecture (cpu/-arch builtin/cpu)
        operating-system (os/-tag builtin/os)]
    (when (and (k/== architecture :.aarch64) (k/!= operating-system :.macos))
      ;; https://github.com/ziglang/zig/issues/14096
      (k/return (az/error-value :SkipZigTest)))
    (when (and (k/== architecture :.x86_64) (k/== operating-system :.windows))
      ;; https://github.com/ziglang/zig/issues/16961
      (k/return (az/error-value :SkipZigTest)))
    (when (k/== architecture :.s390x)
      ;; https://github.com/ziglang/zig/issues/21350#issuecomment-3543006475
      (k/return (az/error-value :SkipZigTest))))

  (try (testing/expectEqual (k/as 0 :c_int) (add 0)))
  (try (testing/expectEqual (k/as 1 :c_int) (add 1 (k/as 1 :c_int))))
  (try (testing/expectEqual (k/as 3 :c_int)
                            (add 2 (k/as 1 :c_int) (k/as 2 :c_int)))))

(comment
  (defining-variadic-function-test))
