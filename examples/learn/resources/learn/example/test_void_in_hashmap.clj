(ns learn.example.test-void-in-hashmap
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std :as std]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest turn-HashMap-into-a-set-with-void
  (let [map (k/var ((:init (std/AutoHashMap :i32 :void))
                    testing/allocator))]
    (defer ((:deinit map)))
    (k/try ((:put map) 1 (a/block)))
    (k/try ((:put map) 2 (a/block)))
    (k/try (testing/expect ((:contains map) 2)))
    (k/try (testing/expect (! ((:contains map) 3))))
    (= :_ ((:remove map) 2))
    (k/try (testing/expect (! ((:contains map) 2))))))

(comment
  (turn-HashMap-into-a-set-with-void))
