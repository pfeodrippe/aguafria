(ns learn.example.struct-name
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn List :type [[T {:attrs #{k/comptime}} :type]]
  (az/struct
   [[:x T]]))

(az/defn main :void []
  (let [Foo (az/struct
             [])]
    (debug/print "variable: {s}\n" [(k/typeName Foo)])
    (debug/print "anonymous: {s}\n"
                 [(k/typeName (az/struct
                               []))])
    (debug/print "function: {s}\n" [(k/typeName (List (az/type :i32)))])))

(comment
  (main))
