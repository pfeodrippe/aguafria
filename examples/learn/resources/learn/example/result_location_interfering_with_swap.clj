(ns learn.example.result-location-interfering-with-swap
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest array-initializer-swap-test
  (let [array (ak/var [1 2] [:array 2 :u32])]
    (ak/= array [(az/index array 1) (az/index array 0)])
    ;; The initializer writes directly into its result location, as if:
    ;;   (ak/= (az/index array 0) (az/index array 1))
    ;;   (ak/= (az/index array 1) (az/index array 0))
    ;; So this fails!
    (try (testing/expectEqual 2 (az/index array 0))) ; succeeds
    (try (testing/expectEqual 1 (az/index array 1))))) ; fails

(comment
  (array-initializer-swap-test))
