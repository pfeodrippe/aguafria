(ns learn.example.struct-name
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn List :type [[T {:zig/prefix "comptime"} :type]]
  (az/struct
    [[:x T]]))

(az/defn main :void []
  (let [Foo (az/struct
              [])]
    (debug/print "variable: {s}\n" [(ak/typeName Foo)])
    (debug/print "anonymous: {s}\n"
                 [(ak/typeName (az/struct
                                 []))])
    (debug/print "function: {s}\n" [(ak/typeName (List (az/type :i32)))])))

(comment
  (main))
