(ns learn.example.test-defining-variadic-function
  (:refer-clojure :only [])
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k :refer :all]
            [aguafria.std.Target.Cpu :as cpu]
            [aguafria.std.Target.Os :as os]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- add :c_int
  {:zig/qualifiers "callconv(.c)"}
  [[count :c_int] [... {:zig/variadic true} _]]
  (let [ap (k/var (cVaStart))]
    (defer (cVaEnd (k/& ap)))
    (let [i (k/var 0 :usize)
          sum (k/var 0 :c_int)]
      (a/while-loop {:continue (a/assign-expr "+=" i 1)}
                    (< i count)
                    (+= sum (cVaArg (k/& ap) :c_int)))
      sum)))

(a/deftest defining-a-variadic-function
  (when (and (== (cpu/-arch builtin/cpu) :aarch64) (!= (os/-tag builtin/os) :macos))
    ;; https://github.com/ziglang/zig/issues/14096
    (return (a/error-value :SkipZigTest)))
  (when (and (== (cpu/-arch builtin/cpu) :x86_64) (== (os/-tag builtin/os) :windows))
    ;; https://github.com/ziglang/zig/issues/16961
    (return (a/error-value :SkipZigTest)))
  (when (== (cpu/-arch builtin/cpu) :s390x)
    ;; https://github.com/ziglang/zig/issues/21350#issuecomment-3543006475
    (return (a/error-value :SkipZigTest)))

  (k/try (testing/expectEqual (as 0 :c_int) (add 0)))
  (k/try (testing/expectEqual (as 1 :c_int) (add 1 (as 1 :c_int))))
  (k/try (testing/expectEqual (as 3 :c_int)
                              (add 2 (as 1 :c_int) (as 2 :c_int)))))

(comment
  (defining-a-variadic-function))
