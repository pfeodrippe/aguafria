(ns learn.example.inline-call
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn- foo :i32
  {:attrs #{k/inline}}
  [[a :i32] [b :i32]]
  (debug/print "runtime a = {} b = {}" [a b])
  (k/+ a b))

(az/defn main :void []
  (when (k/!= (foo 1200 34) 1234)
    (k/compileError "bad")))

(comment
  (main))
