(ns aguafria.zig.discovery-views-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest never-run
  (let [constant (k/i32 12)
        pointer (k/& constant)
        variable (k/var 20 :i32)
        mutable-pointer (k/& variable)
        optional (k/as 7 [:optional :i32])]
    (k/= :_ @pointer)
    (k/= :_ (az/deref pointer))
    (k/+= @mutable-pointer 1)
    (k/+= (az/deref mutable-pointer) 1)
    (k/= :_ (az/unwrap optional)))
  (k/unreachable))
