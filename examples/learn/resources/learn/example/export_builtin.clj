(ns learn.example.export-builtin
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- internalName :void
  {:zig/qualifiers "callconv(.c)"}
  [])

(az/defcomptime export-foo
  (ak/export (& internalName) {:name "foo" :linkage :.strong}))

(comment
  (internalName))
