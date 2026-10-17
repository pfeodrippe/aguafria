(ns aguafria.zig.scalar-alias-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defconst integer-value :i32 1060)
(a/defconst boolean-value :bool true)
(a/defconst float-value :f32 1.5)

(a/defconst inferred-integer (i32 1060))
(a/defconst inferred-boolean (== 1 2))
(a/defconst inferred-float (f32 1.5))

(a/defconst half-float (f16 1.5))
(a/defconst extended-float (f80 1.5))
(a/defconst wide-float (f128 1.5))

(a/defn echo-integer (TypeOf integer-value)
  [[x (TypeOf integer-value)]]
  x)

(a/defn echo-boolean (TypeOf boolean-value)
  [[x (TypeOf boolean-value)]]
  x)

(a/defn echo-float (TypeOf float-value)
  [[x (TypeOf float-value)]]
  x)

(a/defstruct Fields
  [[:number (TypeOf integer-value)]
   [:enabled (TypeOf boolean-value)]
   [:ratio (TypeOf float-value)]])
