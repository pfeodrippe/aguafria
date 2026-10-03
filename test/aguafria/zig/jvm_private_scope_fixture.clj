(ns aguafria.zig.jvm-private-scope-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- parameter-owner :void
  [[handle [:optional [:* :i32]]]]
  (k/= :_ handle))

(a/defn- callback :i32
  {:zig/qualifiers "callconv(.c)"}
  [[x :i32]]
  (k/+ x 1))
