(ns aguafria.zig.discovery-pointers-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest never-run
  (let [number (k/i32 42)
        pointer (k/& number)
        volatile-pointer (k/as pointer [:* {:const? true :volatile? true} :i32])
        aligned-pointer (k/as pointer [:* {:const? true :align 1} :i32])
        zero-pointer (k/as (k/ptrFromInt 0) [:* {:allowzero? true} :i32])
        c-pointer (k/as pointer [:* {:size :c :const? true} :i32])
        array (az/array [1 2] {:sentinel 0} :u8)
        sentinel-pointer (k/as (k/& array) [:sentinel-const :u8 0])]
    (k/= :_ (k/intFromPtr volatile-pointer))
    (k/= :_ (k/intFromPtr aligned-pointer))
    (k/= :_ (k/intFromPtr zero-pointer))
    (k/= :_ (k/intFromPtr c-pointer))
    (k/= :_ (k/intFromPtr sentinel-pointer)))
  (k/unreachable))
