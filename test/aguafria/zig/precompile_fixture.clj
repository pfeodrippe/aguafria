(ns aguafria.zig.precompile-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(let [prefix 40]
  (eval `(a/defconst ~'generated-prefix :i32 ~prefix))
  (eval `(a/defconst ~'generated-suffix :i32 2)))

(a/defn increment :i32
  [[x :i32]]
  (k/+ x 1))

(a/defn subtract :i32
  [[x :i32] [y :i32]]
  (k/- x y))

(a/deftest do-not-run
  (k/unreachable))

;; Leave enough time for an incorrectly completed file prefix to start building.
(Thread/sleep 200)

(a/defn do-not-call :void
  []
  (k/unreachable))

(a/defn generic-identity T
  [[T {:attrs #{k/comptime}} :type] [x T]]
  x)
