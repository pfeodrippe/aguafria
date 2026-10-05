(ns aguafria.zig.discovery-inline-root-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn twice :u32 {:attrs #{k/inline}} [[value :u32]]
  (k/* value 2))

(a/defstruct Container
  [(a/fn- increment :u32 {:attrs #{k/inline}} [[value :u32]]
          (k/+ value 1))])
