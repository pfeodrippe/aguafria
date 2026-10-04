(ns aguafria.zig.discovery-nested-fields-types-fixture
  (:require [aguafria.zig :as a]))

(a/defstruct Request
  [[:parent [:optional [:* Request]]]
   [:actor :u8]])

(a/defstruct Result
  [[:request Request]])

(a/defstruct Exchange
  [[:result Result]])
