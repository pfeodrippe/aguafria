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
       (az/get-in self [:items :len]))]))

(az/deftest this-type-test
  (let [items (k/var (az/array [1 2 3 4] :i32))
        list (az/init {:items (az/slice items 0)} (List :i32))]
    (try (testing/expectEqual 4 ((:length list))))))

(comment
  (this-type-test))
