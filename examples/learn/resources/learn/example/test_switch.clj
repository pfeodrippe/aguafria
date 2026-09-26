(ns learn.example.test-switch
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k]
            [aguafria.std.Target :as target]
            [aguafria.std.Target.Os :as os]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest switch-simple
  (let [a (k/u64 10)
        zz (k/u64 103)
        ;; All branches of a switch expression must be able to be coerced to a
        ;; common type.
        ;;
        ;; Branches cannot fallthrough. If fallthrough behavior is desired, combine
        ;; the cases and use an if.
        b (k/switch a
                 ;; Multiple cases can be combined via a ','
                    (case [1 2 3] 0)
                 ;; Ranges can be specified using the ... syntax. These are inclusive
                 ;; of both ends.
                    (case [(k/... 5 100)] 1)
                 ;; Branches can be arbitrarily complex.
                    (case [101]
                      (az/with-block :blk
                        (let [c (k/u64 5)]
                          (k/break :blk (k/+ (k/* c 2) 1)))))
                 ;; Switching on arbitrary expressions is allowed as long as the
                 ;; expression is known at compile-time.
                    (case [zz] zz)
                    (case [(az/with-block :blk
                             (let [d (k/u32 5)
                                   e (k/u32 100)]
                               (k/break :blk (k/+ d e))))]
                      107)
                 ;; The else branch catches everything not already captured.
                 ;; Else branches are mandatory unless the entire range of values
                 ;; is handled.
                    (az/case-else 9))]
    (try (testing/expectEqual 1 b))))

;; Switch expressions can be used outside a function:
(az/defconst os-msg
  (k/switch (-> builtin/target target/-os os/-tag)
            (case [:.linux] "we found a linux user")
            (az/case-else "not a linux user")))

;; Inside a function, switch statements implicitly are compile-time
;; evaluated if the target expression is compile-time known.
(az/deftest switch-inside-function
  (az/switch-stmt (-> builtin/target target/-os os/-tag)
    ;; On an OS other than fuchsia, block is not even analyzed,
    ;; so this compile error is not triggered.
    ;; On fuchsia this compile error would be triggered.
                  (case [:.fuchsia] (do (k/compileError "fuchsia not supported")))
                  (az/case-else (do))))

(comment
  (switch-simple)
  (switch-inside-function))
