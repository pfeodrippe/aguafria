(ns aguafria.zig.discovery-const-container-caller-fixture
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.zig.discovery-const-container-fixture :as containers]
            [aguafria.keyword :as k :refer :all]))

(a/defn increment :u32
  []
  ((:increment containers/Code) 7))

(a/defn twice :u32
  []
  ((:twice containers/Cell) 7))
