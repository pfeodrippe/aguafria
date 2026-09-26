(ns learn.example.test-void-in-hashmap
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest hashmap-as-set-test
  (let [members (k/var ((:init (aguafria.std/AutoHashMap :i32 :void))
                       testing/allocator))]
    (k/defer ((:deinit members)))
    (try ((:put members) 1 (az/init {} :void)))
    (try ((:put members) 2 (az/init {} :void)))
    (try (testing/expect ((:contains members) 2)))
    (try (testing/expect (k/! ((:contains members) 3))))
    (k/= :_ ((:remove members) 2))
    (try (testing/expect (k/! ((:contains members) 2))))))

(comment
  (hashmap-as-set-test))
