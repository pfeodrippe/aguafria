(ns aguafria.zig.precompile-private-payload-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defstruct Holder
  [(a/struct-decl Payload [[:value :u32]])
   [:maybe [:optional Payload]]])

(a/defunion Command
  {:attrs #{enum}}
  [(a/struct-decl Payload [[:value :u32]])
   [:payload Payload]
   [:empty :void]])

(a/defn read-holder :u32 [[holder Holder]]
  (:value (a/unwrap (:maybe holder))))

(a/defn read-command :u32 [[command Command]]
  (:value (:payload command)))
