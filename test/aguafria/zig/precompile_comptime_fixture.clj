(ns aguafria.zig.precompile-comptime-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defstruct Metadata
  [[:element :type]
   [:count :usize]])

(az/defn metadata Metadata
  [[T {:attrs #{k/comptime}} :type]]
  (Metadata {:element T :count 3}))

(az/defn increment :i32
  [[value :i32]]
  (k/+ value 1))

(az/defstruct CallbackBox
  [[:function (k/TypeOf increment)]])

(az/defn callable CallbackBox
  [[T {:attrs #{k/comptime}} :type]]
  (k/= :_ T)
  (CallbackBox {:function increment}))
