(ns aguafria.zig.precompile-noreturn-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn never-run :noreturn
  []
  (a/while-loop {} true))

(a/defn direct-panic :noreturn
  []
  (k/panic "prepared noreturn panic"))

(a/defn indirect-panic :noreturn
  [[message [:slice-const :u8]]]
  (k/panic message))
