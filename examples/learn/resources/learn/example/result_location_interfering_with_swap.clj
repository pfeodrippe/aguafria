(ns learn.example.result-location-interfering-with-swap
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest attempt-to-swap-array-elements-with-array-initializer
  (let [arr (k/var [1 2] [:array 2 :u32])]
    (= arr [(a/get arr 1) (a/get arr 0)])
    ;; The previous line is equivalent to the following two lines:
    ;;   arr[0] = arr[1];
    ;;   arr[1] = arr[0];
    ;; So this fails!
    (k/try (testing/expectEqual 2 (a/get arr 0))) ; succeeds
    (k/try (testing/expectEqual 1 (a/get arr 1))))) ; fails

(comment
  (attempt-to-swap-array-elements-with-array-initializer))
