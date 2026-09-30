(ns aguafria.zig.precompile-noreturn-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn never-run :noreturn
  []
  (az/while-loop {} true))

(az/defn direct-panic :noreturn
  []
  (k/panic "prepared noreturn panic"))

(az/defn indirect-panic :noreturn
  [[message [:slice-const :u8]]]
  (k/panic message))
