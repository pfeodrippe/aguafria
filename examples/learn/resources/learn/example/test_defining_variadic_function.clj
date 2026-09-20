(ns learn.example.test-defining-variadic-function
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst builtin (ak/import "builtin"))

(az/defn- add :c_int
  {:zig/qualifiers "callconv(.c)"}
  [[count :c_int] [... {:zig/variadic true} _]]
  (let [^:var arguments (ak/cVaStart)]
    (ak/defer (ak/cVaEnd (& arguments)))
    (let [^:var index (ak/usize 0)
          ^:var sum (ak/as 0 :c_int)]
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

  (try (testing/expectEqual (ak/as 0 :c_int) (add 0)))
  (try (testing/expectEqual (ak/as 1 :c_int) (add 1 (ak/as 1 :c_int))))
  (try (testing/expectEqual (ak/as 3 :c_int)
                            (add 2 (ak/as 1 :c_int) (ak/as 2 :c_int)))))

(comment
  (defining-variadic-function-test))
