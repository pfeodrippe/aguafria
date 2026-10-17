(ns aguafria.zig.namespace-admission-naked-reference-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn- stop-forever :noreturn []
  (a/while-loop {} true))

(a/defn entry :noreturn {:zig/qualifiers "callconv(.naked)"} []
  (stop-forever))

(a/deftest referenced-entry
  (= :_ (k/& entry)))
