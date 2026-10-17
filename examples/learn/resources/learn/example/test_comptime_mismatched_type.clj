(ns learn.example.test-comptime-mismatched-type
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn- max T
  [[T {:attrs #{comptime}} :type] [a T] [b T]]
  (if (> a b) a b))

(a/deftest try-to-compare-bools
  (= :_ (max :bool true false)))

(comment
  (try-to-compare-bools))
