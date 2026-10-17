(ns aguafria.zig.precompile-noreturn-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn never-run :noreturn
  []
  (a/while-loop {} true))

(a/defn direct-panic :noreturn
  []
  (panic "prepared noreturn panic"))

(a/defn indirect-panic :noreturn
  [[message [:slice-const :u8]]]
  (panic message))
