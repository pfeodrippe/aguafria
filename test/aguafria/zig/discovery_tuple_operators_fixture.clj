(ns aguafria.zig.discovery-tuple-operators-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/deftest never-run
  (= :_ (bool false))
  (= :_ (a/string-literal "\"hi\""))
  (= :_ (as (splat 0) [:array 5 :i32]))
  (= :_ (as (splat (splat 0)) [:array 4 [:array 5 :i32]]))
  (= :_ (as (splat false) [:array 2 :bool]))
  (let [values (++ [(u32 1234) (f64 12.34) true "hi"] [false false])]
    (= :_ (a/get values 0))
    (= :_ (a/get values 4))
    (= :_ (a/field values :len))
    (= :_ (a/get-in values [:3 0])))
  (unreachable))
