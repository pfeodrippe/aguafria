(ns aguafria.zig.union-payload-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defenum Tag [:ok :empty])
(a/defunion Payload {:type Tag} [[:ok :u8] [:empty :void]])

(a/defn increment! :void [[payload [:* Payload]]]
  (switch (a/deref payload)
    (:ok Tag) (a/with-block :_ [(* item)] (a/block (+= @item 1)))
    (:empty Tag) (unreachable)))

(a/defstruct Aligned [[:value {:align 64} :u8]])
(a/defunion AlignedPayload {:enum? true}
  [[:aligned Aligned] [:empty :void]])
(a/defunion SlicePayload {:enum? true}
  [[:bytes [:slice :u8]] [:empty :void]])
