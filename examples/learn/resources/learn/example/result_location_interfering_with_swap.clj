(ns learn.example.result-location-interfering-with-swap
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest attempt-to-swap-array-elements-with-array-initializer
  (let [arr (k/var [1 2] [:array 2 :u32])]
    (k/= arr [(az/get arr 1) (az/get arr 0)])
    ;; The previous line is equivalent to the following two lines:
    ;;   arr[0] = arr[1];
    ;;   arr[1] = arr[0];
    ;; So this fails!
    (try (testing/expectEqual 2 (az/get arr 0))) ; succeeds
    (try (testing/expectEqual 1 (az/get arr 1))))) ; fails

(comment
  (attempt-to-swap-array-elements-with-array-initializer))
