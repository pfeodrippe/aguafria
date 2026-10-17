(ns aguafria.zig.discovery-comptime-alias-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defunion Tagged {:enum? true :type :u8}
  [[:count :u32]
   [:empty :void]])

(a/defn tag-count :usize []
  (let [Tag (a/unwrap (:tag_type (:union (typeInfo Tagged))))
        info (:enum (typeInfo Tag))
        names (:field_names info)
        values (:field_values info)]
    (+ (:len names) (:len values))))

(a/defn- parameter-identity :bool []
  (let [info (:fn (typeInfo (TypeOf tag-count)))
        types (:param_types info)]
    (== (:len types) 0)))

(a/defn- consume :u32 [[n :u32]] n)

(a/defn- parameter-type :bool []
  (let [info (:fn (typeInfo (TypeOf consume)))
        types (:param_types info)]
    (== (a/unwrap (a/get types 0)) (a/type :u32))))
