(ns aguafria.zig.linkage-context-second-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn implementation :i32 {:zig/qualifiers "callconv(.c)"} [] 22)

(a/defcomptime publish-foo
  (k/export (k/& implementation) {:name "foo" :linkage :strong}))
