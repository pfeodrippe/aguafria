(ns aguafria.zig.discovery-pointers-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/deftest never-run
  (let [number (i32 42)
        pointer (k/& number)
        volatile-pointer (as pointer [:* {:const? true :volatile? true} :i32])
        aligned-pointer (as pointer [:* {:const? true :align 1} :i32])
        zero-pointer (as (ptrFromInt 0) [:* {:allowzero? true} :i32])
        c-pointer (as pointer [:* {:size :c :const? true} :i32])
        array (a/array [1 2] {:sentinel 0} :u8)
        sentinel-pointer (as (k/& array) [:sentinel-const :u8 0])]
    (= :_ (intFromPtr volatile-pointer))
    (= :_ (intFromPtr aligned-pointer))
    (= :_ (intFromPtr zero-pointer))
    (= :_ (intFromPtr c-pointer))
    (= :_ (intFromPtr sentinel-pointer)))
  (unreachable))
