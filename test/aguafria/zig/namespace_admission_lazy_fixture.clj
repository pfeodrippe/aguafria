(ns aguafria.zig.namespace-admission-lazy-fixture
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defextern unused-foreign :i32
  {:zig/prefix "extern \"__aguafria_unavailable_test_library__\""}
  [])

(a/defn- stop-forever :noreturn []
  (a/while-loop {} true))

;; Like Zig's function documentation, this body is only invalid when analyzed.
;; It cannot be called as an ordinary function and is not an inspection root.
(a/defn entry :noreturn {:zig/qualifiers "callconv(.naked)"} []
  (stop-forever))

(a/defn answer :i32 [] 42)
