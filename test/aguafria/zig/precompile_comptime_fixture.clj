(ns aguafria.zig.precompile-comptime-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defstruct Metadata
  [[:element :type]
   [:count :usize]])

(a/defn metadata Metadata
  [[T {:attrs #{k/comptime}} :type]]
  (Metadata {:element T :count 3}))

(a/defn increment :i32
  [[value :i32]]
  (k/+ value 1))

(a/defstruct CallbackBox
  [[:function (k/TypeOf increment)]])

(a/defn callable CallbackBox
  [[T {:attrs #{k/comptime}} :type]]
  (k/= :_ T)
  (CallbackBox {:function increment}))
