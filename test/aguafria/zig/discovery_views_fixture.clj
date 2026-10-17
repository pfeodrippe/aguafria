(ns aguafria.zig.discovery-views-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/deftest never-run
  (let [constant (i32 12)
        pointer (k/& constant)
        variable (k/var 20 :i32)
        mutable-pointer (k/& variable)
        optional (as 7 [:optional :i32])]
    (= :_ @pointer)
    (= :_ (a/deref pointer))
    (+= @mutable-pointer 1)
    (+= (a/deref mutable-pointer) 1)
    (= :_ (a/unwrap optional)))
  (unreachable))
