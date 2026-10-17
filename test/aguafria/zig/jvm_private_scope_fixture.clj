(ns aguafria.zig.jvm-private-scope-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn- parameter-owner :void
  [[handle [:optional [:* :i32]]]]
  (= :_ handle))

(a/defn- callback :i32
  {:zig/qualifiers "callconv(.c)"}
  [[x :i32]]
  (+ x 1))
