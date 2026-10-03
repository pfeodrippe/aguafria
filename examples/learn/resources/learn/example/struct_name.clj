(ns learn.example.struct-name
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn List :type [[T {:attrs #{k/comptime}} :type]]
  (a/struct
   [[:x T]]))

(a/defn main :void []
  (let [Foo (a/struct
             [])]
    (debug/print "variable: {s}\n" [(k/typeName Foo)])
    (debug/print "anonymous: {s}\n"
                 [(k/typeName (a/struct
                               []))])
    (debug/print "function: {s}\n" [(k/typeName (List :i32))])))

(comment
  (main))
