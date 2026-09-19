(ns learn.examples.idiomatic-metaprogramming.inline-call
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn- add-at-callsite :i32
  {:zig/prefix "inline"}
  [[left :i32] [right :i32]]
  (debug/print "runtime a = {} b = {}" [left right])
  (+ left right))

(az/defn main :void []
  (when (ak/!= (add-at-callsite 1200 34) 1234)
    (ak/compileError "bad")))
