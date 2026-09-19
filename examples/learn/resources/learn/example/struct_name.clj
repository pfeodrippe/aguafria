(ns learn.example.struct-name
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn List :type [[T {:zig/prefix "comptime"} :type]]
  (az/container {:kind :struct}
    (az/field-decl :x T)))

(az/defn main :void []
  (let [Foo (az/container {:kind :struct})]
    (debug/print "variable: {s}\n" [(ak/typeName Foo)])
    (debug/print "anonymous: {s}\n"
                 [(ak/typeName (az/container {:kind :struct}))])
    (debug/print "function: {s}\n" [(ak/typeName (List (az/type :i32)))])))
