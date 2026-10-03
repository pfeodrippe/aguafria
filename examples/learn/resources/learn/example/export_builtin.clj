(ns learn.example.export-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- internalName :void
  {:zig/qualifiers "callconv(.c)"}
  [])

(a/defcomptime export-foo
  (k/export (k/& internalName) {:name "foo" :linkage :.strong}))

(comment
  (internalName))
