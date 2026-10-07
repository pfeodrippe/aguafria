(ns aguafria.zig.scalar-alias-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defconst integer-value :i32 1060)
(a/defconst boolean-value :bool true)
(a/defconst float-value :f32 1.5)

(a/defconst inferred-integer (k/i32 1060))
(a/defconst inferred-boolean (k/== 1 2))
(a/defconst inferred-float (k/f32 1.5))

(a/defconst half-float (k/f16 1.5))
(a/defconst extended-float (k/f80 1.5))
(a/defconst wide-float (k/f128 1.5))

(a/defn echo-integer (k/TypeOf integer-value)
  [[x (k/TypeOf integer-value)]]
  x)

(a/defn echo-boolean (k/TypeOf boolean-value)
  [[x (k/TypeOf boolean-value)]]
  x)

(a/defn echo-float (k/TypeOf float-value)
  [[x (k/TypeOf float-value)]]
  x)

(a/defstruct Fields
  [[:number (k/TypeOf integer-value)]
   [:enabled (k/TypeOf boolean-value)]
   [:ratio (k/TypeOf float-value)]])
