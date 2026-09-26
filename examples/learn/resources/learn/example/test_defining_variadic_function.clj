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
  (let [ap (k/var (k/cVaStart))]
    (k/defer (k/cVaEnd (k/& ap)))
    (let [i (k/var 0 :usize)
          sum (k/var 0 :c_int)]
      (az/while-loop {:continue (az/assign-expr "+=" i 1)}
                     (k/< i count)
                     (k/+= sum (k/cVaArg (k/& ap) :c_int)))
      sum)))

(az/deftest defining-a-variadic-function
  (when (and (k/== (cpu/-arch builtin/cpu) :.aarch64) (k/!= (os/-tag builtin/os) :.macos))
      ;; https://github.com/ziglang/zig/issues/14096
    (k/return (az/error-value :SkipZigTest)))
  (when (and (k/== (cpu/-arch builtin/cpu) :.x86_64) (k/== (os/-tag builtin/os) :.windows))
      ;; https://github.com/ziglang/zig/issues/16961
    (k/return (az/error-value :SkipZigTest)))
  (when (k/== (cpu/-arch builtin/cpu) :.s390x)
      ;; https://github.com/ziglang/zig/issues/21350#issuecomment-3543006475
    (k/return (az/error-value :SkipZigTest)))

  (try (testing/expectEqual (k/as 0 :c_int) (add 0)))
  (try (testing/expectEqual (k/as 1 :c_int) (add 1 (k/as 1 :c_int))))
  (try (testing/expectEqual (k/as 3 :c_int)
                            (add 2 (k/as 1 :c_int) (k/as 2 :c_int)))))

(comment
  (defining-a-variadic-function))
