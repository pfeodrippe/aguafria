(ns learn.example.inline-call
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn- foo :i32
  {:attrs #{ak/inline}}
  [[a :i32] [b :i32]]
  (debug/print "runtime a = {} b = {}" [a b])
  (+ a b))

(az/defn main :void []
  (when (ak/!= (foo 1200 34) 1234)
    (ak/compileError "bad")))

(comment
  (main))
