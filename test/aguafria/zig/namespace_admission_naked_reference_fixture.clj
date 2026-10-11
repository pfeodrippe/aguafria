(ns aguafria.zig.namespace-admission-naked-reference-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- stop-forever :noreturn []
  (a/while-loop {} true))

(a/defn entry :noreturn {:zig/qualifiers "callconv(.naked)"} []
  (stop-forever))

(a/deftest referenced-entry
  (k/= :_ (k/& entry)))
