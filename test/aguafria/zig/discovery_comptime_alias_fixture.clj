(ns aguafria.zig.discovery-comptime-alias-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defunion Tagged {:enum? true :type :u8}
  [[:count :u32]
   [:empty :void]])

(a/defn tag-count :usize []
  (let [Tag (a/unwrap (:tag_type (:union (k/typeInfo Tagged))))
        info (:enum (k/typeInfo Tag))
        names (:field_names info)
        values (:field_values info)]
    (k/+ (:len names) (:len values))))

(a/defn- parameter-identity :bool []
  (let [info (:fn (k/typeInfo (k/TypeOf tag-count)))
        types (:param_types info)]
    (k/== (:len types) 0)))

(a/defn- consume :u32 [[n :u32]] n)

(a/defn- parameter-type :bool []
  (let [info (:fn (k/typeInfo (k/TypeOf consume)))
        types (:param_types info)]
    (k/== (a/unwrap (a/get types 0)) (a/type :u32))))
