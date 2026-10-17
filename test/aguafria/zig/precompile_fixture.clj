(ns aguafria.zig.precompile-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]
            [clojure.core :as c]))

(let [prefix 40]
  (c/eval `(a/defconst ~'generated-prefix :i32 ~prefix))
  (c/eval `(a/defconst ~'generated-suffix :i32 2)))

(a/defn increment :i32
  [[x :i32]]
  (+ x 1))

(a/defn subtract :i32
  [[x :i32] [y :i32]]
  (- x y))

(a/deftest do-not-run
  (unreachable))

;; Leave enough time for an incorrectly completed file prefix to start building.
(Thread/sleep 200)

(a/defn do-not-call :void
  []
  (unreachable))

(a/defn generic-identity T
  [[T {:attrs #{comptime}} :type] [x T]]
  x)
