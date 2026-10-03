(ns aguafria.zig.discovery-tuple-operators-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/deftest never-run
  (k/= :_ (k/bool false))
  (k/= :_ (a/string-literal "\"hi\""))
  (k/= :_ (k/as (k/splat 0) [:array 5 :i32]))
  (k/= :_ (k/as (k/splat (k/splat 0)) [:array 4 [:array 5 :i32]]))
  (k/= :_ (k/as (k/splat false) [:array 2 :bool]))
  (let [values (k/++ [(k/u32 1234) (k/f64 12.34) true "hi"] [false false])]
    (k/= :_ (a/get values 0))
    (k/= :_ (a/get values 4))
    (k/= :_ (a/field values :len))
    (k/= :_ (a/get-in values [:3 0])))
  (k/unreachable))
