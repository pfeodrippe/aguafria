(ns learn.example.test-type-coercion
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn foo :void
  [[value :u16]]
  (ak/= :_ value))

(az/deftest declaration-coercion-test
  (let [narrow (ak/u8 1)
        wide (ak/u16 narrow)]
    (ak/= :_ wide)))

(az/deftest argument-coercion-test
  (let [narrow (ak/u8 1)]
    (foo narrow)))

(az/deftest explicit-coercion-test
  (let [narrow (ak/u8 1)
        wide (ak/as narrow (az/type :u16))]
    (ak/= :_ wide)))

(comment
  (declaration-coercion-test)
  (argument-coercion-test)
  (explicit-coercion-test))
