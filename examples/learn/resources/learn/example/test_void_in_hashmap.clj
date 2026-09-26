(ns learn.example.test-void-in-hashmap
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest turn-HashMap-into-a-set-with-void
  (let [map (k/var ((:init (aguafria.std/AutoHashMap :i32 :void))
                    testing/allocator))]
    (k/defer ((:deinit map)))
    (try ((:put map) 1 (az/init {} :void)))
    (try ((:put map) 2 (az/init {} :void)))
    (try (testing/expect ((:contains map) 2)))
    (try (testing/expect (k/! ((:contains map) 3))))
    (k/= :_ ((:remove map) 2))
    (try (testing/expect (k/! ((:contains map) 2))))))

(comment
  (turn-HashMap-into-a-set-with-void))
