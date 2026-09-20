(ns learn.example.test-this-builtin
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest this-type-test
  (let [items (ak/var (az/array-init [1 2 3 4] [:array :_ :i32]))
        list (az/init {:items (az/slice items 0)} (List :i32))]
    (try (testing/expectEqual 4 ((az/field list :length))))))

(az/defn- List :type
  [[T {:zig/prefix "comptime"} :type]]
  (az/struct
    [(az/const-decl Self (ak/This))
     [:items [:slice T]]
     (az/fn-decl length :usize
       [[self Self]]
       (az/field (az/field self :items) :len))]))

(comment
  (this-type-test))
