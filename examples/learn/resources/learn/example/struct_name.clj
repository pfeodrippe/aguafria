(ns learn.example.struct-name
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn List :type [[T {:attrs #{comptime}} :type]]
  (a/struct
   [[:x T]]))

(a/defn main :void []
  (let [Foo (a/struct
             [])]
    (debug/print "variable: {s}\n" [(typeName Foo)])
    (debug/print "anonymous: {s}\n"
                 [(typeName (a/struct
                             []))])
    (debug/print "function: {s}\n" [(typeName (List :i32))])))

(comment
  (main))
