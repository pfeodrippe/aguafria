(ns learn.example.test-type-coercion
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn foo :void
  [[value :u16]]
  (k/= :_ value))

(az/deftest declaration-coercion-test
  (let [narrow (k/u8 1)
        wide (k/u16 narrow)]
    (k/= :_ wide)))

(az/deftest argument-coercion-test
  (let [narrow (k/u8 1)]
    (foo narrow)))

(az/deftest explicit-coercion-test
  (let [narrow (k/u8 1)
        wide (k/as narrow (az/type :u16))]
    (k/= :_ wide)))

(comment
  (declaration-coercion-test)
  (argument-coercion-test)
  (explicit-coercion-test))
