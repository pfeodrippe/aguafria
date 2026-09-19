(ns learn.examples.idiomatic-state.result-location-interfering-with-swap
  "Converted from result_location_interfering_with_swap.zig"
  (:require aguafria.std
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest array-initializer-swap-test
  (let [^{:var [:array 2 :u32]} array [1 2]]
    (set! array [(az/index array 1) (az/index array 0)])
    ;; The initializer writes directly into its result location, as if:
    ;;   (set! (az/index array 0) (az/index array 1))
    ;;   (set! (az/index array 1) (az/index array 0))
    ;; So this fails!
    (try (testing/expectEqual 2 (az/index array 0))) ; succeeds
    (try (testing/expectEqual 1 (az/index array 1))))) ; fails
