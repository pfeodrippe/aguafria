(ns aguafria.zig.discovery-union-tag-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defunion Tagged {:enum? true :type :u8}
  [[:count :u32]
   [:empty :void]])

(a/defn tag-size :usize []
  (let [Tag (a/unwrap (:tag_type (:union (k/typeInfo Tagged))))]
    (k/sizeOf Tag)))
