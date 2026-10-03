(ns aguafria.zig.discovery-tuple-operators-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest never-run
  (k/= :_ (k/bool false))
  (k/= :_ (az/string-literal "\"hi\""))
  (k/= :_ (k/as (k/splat 0) [:array 5 :i32]))
  (k/= :_ (k/as (k/splat (k/splat 0)) [:array 4 [:array 5 :i32]]))
  (k/= :_ (k/as (k/splat false) [:array 2 :bool]))
  (let [values (k/++ [(k/u32 1234) (k/f64 12.34) true "hi"] [false false])]
    (k/= :_ (az/get values 0))
    (k/= :_ (az/get values 4))
    (k/= :_ (az/field values :len))
    (k/= :_ (az/get-in values [:3 0])))
  (k/unreachable))
