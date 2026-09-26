(ns learn.example.result-location-interfering-with-swap
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest array-initializer-swap-test
  (let [array (k/var [1 2] [:array 2 :u32])]
    (k/= array [(az/get array 1) (az/get array 0)])
    ;; The initializer writes directly into its result location, as if:
    ;;   (k/= (az/get array 0) (az/get array 1))
    ;;   (k/= (az/get array 1) (az/get array 0))
    ;; So this fails!
    (try (testing/expectEqual 2 (az/get array 0))) ; succeeds
    (try (testing/expectEqual 1 (az/get array 1))))) ; fails

(comment
  (array-initializer-swap-test))
