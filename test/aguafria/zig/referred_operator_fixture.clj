(ns aguafria.zig.referred-operator-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]
            [clojure.core :as c]))

(a/defn arithmetic :i32 [[x :i32] [y :i32]]
  (+ x (* y 2)))

(a/defn boolean-or :bool [[a :bool] [b :bool]]
  (or a b))

(a/defn threaded :i32 [[x :i32]]
  (-> x (+ 2) (* 3)))

(a/defn named-thread :i32 [[x :i32]]
  (as-> x value (+ value 2) (* value 3)))

(c/defn captured-comptime []
  (let [x 3]
    (comptime (+ x 4))))
