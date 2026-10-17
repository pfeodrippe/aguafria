(ns aguafria.zig.discovery-inline-root-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn twice :u32 {:attrs #{inline}} [[value :u32]]
  (* value 2))

(a/defstruct Container
  [(a/fn- increment :u32 {:attrs #{inline}} [[value :u32]]
          (+ value 1))])
