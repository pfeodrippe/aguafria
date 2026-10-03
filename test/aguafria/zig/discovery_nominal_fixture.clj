(ns aguafria.zig.discovery-nominal-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defenum Tag [:integer :empty])

(a/defconst Payload
  (a/union {:type Tag} [[:integer :i32] [:empty :void]]))

(a/defconst Raw
  (a/union [[:integer :i32] [:floating :f32]]))

(a/defstruct Counter [[:value {:var 10} :i32]
                       [:initial {:const 10} :i32]])

(a/deftest never-run
  (let [tag (:integer Tag)
        payload (Payload {:integer 42})
        raw (Raw {:integer 12})]
    (k/= :_ tag)
    (k/= :_ (:integer payload))
    (k/= :_ (:integer raw))
    (k/= :_ (:initial Counter))
    (k/+= (:value Counter) 1))
  (k/unreachable))
