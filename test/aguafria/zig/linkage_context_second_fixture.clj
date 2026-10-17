(ns aguafria.zig.linkage-context-second-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn implementation :i32 {:zig/qualifiers "callconv(.c)"} [] 22)

(a/defcomptime publish-foo
  (export (k/& implementation) {:name "foo" :linkage :strong}))
