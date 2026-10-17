(ns aguafria.zig.contextual-address-prepared-fixture
  (:refer-clojure :only [let])
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn direct-address :i32 []
  (let [slice (k/as (k/& [1 2 3 4]) [:slice-const :i32])]
    (a/get slice 2)))
