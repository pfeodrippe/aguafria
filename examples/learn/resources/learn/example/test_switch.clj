(ns learn.example.test-switch
  (:refer-clojure :only [])
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k :refer :all]
            [aguafria.std.Target :as target]
            [aguafria.std.Target.Os :as os]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest switch-simple
  (let [a (u64 10)
        zz (u64 103)
        ;; All branches of a switch expression must be able to be coerced to a
        ;; common type.
        ;;
        ;; Branches cannot fallthrough. If fallthrough behavior is desired, combine
        ;; the cases and use an if.
        b (switch a
            ;; Multiple cases can be combined via a ','
            (1 2 3) 0
            ;; Ranges can be specified using the ... syntax. These are inclusive
            ;; of both ends.
            (k/... 5 100) 1
            ;; Branches can be arbitrarily complex.
            101
            (a/with-block :blk
              (let [c (u64 5)]
                (break :blk (+ (* c 2) 1))))
            ;; Switching on arbitrary expressions is allowed as long as the
            ;; expression is known at compile-time.
            zz zz
            (a/with-block :blk
              (let [d (u32 5)
                    e (u32 100)]
                (break :blk (+ d e))))
            107
            ;; The else branch catches everything not already captured.
            ;; Else branches are mandatory unless the entire range of values
            ;; is handled.
            9)]
    (k/try (testing/expectEqual 1 b))))

;; Switch expressions can be used outside a function:
(a/defconst os-msg
  (switch (-> builtin/target target/-os os/-tag)
    :linux "we found a linux user"
    "not a linux user"))

;; Inside a function, switch statements implicitly are compile-time
;; evaluated if the target expression is compile-time known.
(a/deftest switch-inside-function
  (switch (-> builtin/target target/-os os/-tag)
    ;; On an OS other than fuchsia, block is not even analyzed,
    ;; so this compile error is not triggered.
    ;; On fuchsia this compile error would be triggered.
    :fuchsia (do (compileError "fuchsia not supported"))
    (do)))

(comment
  (switch-simple)
  (switch-inside-function))
