(ns learn.example.export-builtin
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn- internalName :void
  {:zig/qualifiers "callconv(.c)"}
  [])

(a/defcomptime export-foo
  (export (k/& internalName) {:name "foo" :linkage :strong}))

(comment
  (internalName))
