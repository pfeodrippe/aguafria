(ns learn.example.test-switch
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k]
            [aguafria.std.Target :as target]
            [aguafria.std.Target.Os :as os]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest switch-simple-test
  (let [value (k/u64 10)
        special-value (k/u64 103)
        result (k/switch value
                 (case [1 2 3] 0)
                 ;; Ranges include both endpoints; cases never fall through.
                 (case [(k/... 5 100)] 1)
                 (case [101]
                   (let [base (k/u64 5)]
                     (k/+ (k/* base 2) 1)))
                 (case [special-value] special-value)
                 ;; Case expressions may themselves compute a comptime value.
                 (case [(let [lower (k/u32 5)
                              upper (k/u32 100)]
                          (k/+ lower upper))]
                   107)
                 (az/case-else 9))]
    (try (testing/expectEqual 1 result))))

(az/defconst target-os
  (-> builtin/target
      target/-os
      os/-tag))

(az/defconst os-message
  (k/switch target-os
    (case [:.linux] "we found a linux user")
    (az/case-else "not a linux user")))

(az/deftest switch-inside-function-test
  ;; The unselected branch is not analyzed when the target is comptime-known.
  (az/switch-stmt target-os
    (case [:.fuchsia] (do (k/compileError "fuchsia not supported")))
    (az/case-else (do))))

(comment
  (switch-simple-test)
  (switch-inside-function-test))
