(ns learn.example.test-this-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- List :type
  [[T {:attrs #{k/comptime}} :type]]
  (a/struct
   [[:Self {:const (k/This)} :type]
    [:items [:slice T]]
    (a/fn- length :usize
           [[self Self]]
           (a/get-in self [:items :len]))]))

(a/deftest This
  (let [items (k/var (a/array [1 2 3 4] :i32))
        list (a/init {:items (a/slice items 0)} (List :i32))]
    (try (testing/expectEqual 4 ((:length list))))))

(comment
  (This))
