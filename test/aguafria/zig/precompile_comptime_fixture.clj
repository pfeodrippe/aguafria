(ns aguafria.zig.precompile-comptime-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defstruct Metadata
  [[:element :type]
   [:count :usize]])

(a/defn metadata Metadata
  [[T {:attrs #{comptime}} :type]]
  (Metadata {:element T :count 3}))

(a/defn increment :i32
  [[value :i32]]
  (+ value 1))

(a/defstruct CallbackBox
  [[:function (TypeOf increment)]])

(a/defn callable CallbackBox
  [[T {:attrs #{comptime}} :type]]
  (= :_ T)
  (CallbackBox {:function increment}))
