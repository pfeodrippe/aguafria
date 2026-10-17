(ns learn.example.inline-call
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn- foo :i32
  {:attrs #{inline}}
  [[a :i32] [b :i32]]
  (debug/print "runtime a = {} b = {}" [a b])
  (+ a b))

(a/defn main :void []
  (when (!= (foo 1200 34) 1234)
    (compileError "bad")))

(comment
  (main))
