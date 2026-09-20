(ns learn.example.export-builtin
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime export-foo
  (ak/export (& internalName) {:name "foo" :linkage :.strong}))

(az/defn- internalName :void
  {:zig/qualifiers "callconv(.c)"}
  [])

(comment
  (internalName))
