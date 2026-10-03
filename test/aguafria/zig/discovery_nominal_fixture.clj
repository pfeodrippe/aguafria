(ns aguafria.zig.discovery-nominal-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defenum Tag [:integer :empty])

(az/defconst Payload
  (az/union {:type Tag} [[:integer :i32] [:empty :void]]))

(az/defconst Raw
  (az/union [[:integer :i32] [:floating :f32]]))

(az/defstruct Counter [[:value {:var 10} :i32]
                       [:initial {:const 10} :i32]])

(az/deftest never-run
  (let [tag (:integer Tag)
        payload (Payload {:integer 42})
        raw (Raw {:integer 12})]
    (k/= :_ tag)
    (k/= :_ (:integer payload))
    (k/= :_ (:integer raw))
    (k/= :_ (:initial Counter))
    (k/+= (:value Counter) 1))
  (k/unreachable))
