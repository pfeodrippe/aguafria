(ns learn.example.test-void-in-hashmap
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest hashmap-as-set-test
  (let [members (k/var ((az/field (aguafria.std/AutoHashMap :i32 :void) :init)
                       testing/allocator))]
    (k/defer ((az/field members :deinit)))
    (try ((az/field members :put) 1 (az/init {} :void)))
    (try ((az/field members :put) 2 (az/init {} :void)))
    (try (testing/expect ((az/field members :contains) 2)))
    (try (testing/expect (k/! ((az/field members :contains) 3))))
    (k/= :_ ((az/field members :remove) 2))
    (try (testing/expect (k/! ((az/field members :contains) 2))))))

(comment
  (hashmap-as-set-test))
