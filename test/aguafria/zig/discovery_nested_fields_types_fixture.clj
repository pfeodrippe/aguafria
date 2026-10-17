(ns aguafria.zig.discovery-nested-fields-types-fixture
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defstruct Request
  [[:parent [:optional [:* Request]]]
   [:actor :u8]])

(a/defstruct Result
  [[:request Request]])

(a/defstruct Exchange
  [[:result Result]])
