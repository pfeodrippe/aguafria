(ns learn.example.export-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- internalName :void
  {:zig/qualifiers "callconv(.c)"}
  [])

(az/defcomptime export-foo
  (k/export (k/& internalName) {:name "foo" :linkage :.strong}))

(comment
  (internalName))
