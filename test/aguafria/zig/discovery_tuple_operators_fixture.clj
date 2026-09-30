(ns aguafria.zig.discovery-tuple-operators-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest never-run
  (k/= :_ (k/bool false))
  (k/= :_ (az/string-literal "\"hi\""))
  (k/= :_ (k/** [0] 5))
  (k/= :_ (k/** [(k/** [0] 5)] 4))
  (k/= :_ (k/** [false] 2))
  (let [values (k/++ [(k/u32 1234) (k/f64 12.34) true "hi"] [false false])]
    (k/= :_ (az/get values 0))
    (k/= :_ (az/get values 4))
    (k/= :_ (az/field values :len))
    (k/= :_ (az/get-in values [:3 0])))
  (k/unreachable))
