(ns learn.examples.idiomatic-interop.export-builtin
  "Converted from export_builtin.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime export-foo
  (ak/export (& internalName) {:name "foo" :linkage :.strong}))

(az/defn- internalName :void
  {:zig/qualifiers "callconv(.c)"}
  [])
