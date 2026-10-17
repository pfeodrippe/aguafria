(ns aguafria.zig.discovery-union-tag-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defunion Tagged {:enum? true :type :u8}
  [[:count :u32]
   [:empty :void]])

(a/defn tag-size :usize []
  (let [Tag (a/unwrap (:tag_type (:union (typeInfo Tagged))))]
    (sizeOf Tag)))
