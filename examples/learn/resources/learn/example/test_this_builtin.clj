(ns learn.example.test-this-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- List :type
  [[T {:attrs #{k/comptime}} :type]]
  (az/struct
    [[:Self {:const (k/This)} :type]
     [:items [:slice T]]
     (az/fn- length :usize
       [[self Self]]
       (az/field (az/field self :items) :len))]))

(az/deftest this-type-test
  (let [items (k/var (az/array-init [1 2 3 4] [:array :_ :i32]))
        list (az/init {:items (az/slice items 0)} (List :i32))]
    (try (testing/expectEqual 4 ((az/field list :length))))))

(comment
  (this-type-test))
