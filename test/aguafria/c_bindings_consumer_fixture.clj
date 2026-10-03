(ns aguafria.c-bindings-consumer-fixture
  (:require [aguafria.c-bindings-fixture :as native]
            [aguafria.zig :as az]))

(az/defn sum :c_int [[x :c_int] [y :c_int]]
  (native/point_sum (az/init {:x x :y y} native/native_point)))

(az/defn sum-through-api :c_int [[x :c_int] [y :c_int]]
  ((:point_sum native/api) (az/init {:x x :y y} (:native_point native/api))))
