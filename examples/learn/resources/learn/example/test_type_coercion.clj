(ns learn.example.test-type-coercion
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn accept-wide-integer :void
  [[value :u16]]
  (set! _ value))

(az/deftest declaration-coercion-test
  (let [^{:zig/type :u8} narrow 1
        ^{:zig/type :u16} wide narrow]
    (set! _ wide)))

(az/deftest argument-coercion-test
  (let [^{:zig/type :u8} narrow 1]
    (accept-wide-integer narrow)))

(az/deftest explicit-coercion-test
  (let [^{:zig/type :u8} narrow 1
        wide (ak/as (az/type :u16) narrow)]
    (set! _ wide)))
