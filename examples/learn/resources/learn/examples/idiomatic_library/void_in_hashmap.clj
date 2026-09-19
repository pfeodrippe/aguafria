(ns learn.examples.idiomatic-library.void-in-hashmap
  "Converted from test_void_in_hashmap.zig"
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest hashmap-as-set-test
  (let [^:var members ((az/field (aguafria.std/AutoHashMap :i32 :void) :init)
                       testing/allocator)]
    (ak/defer ((az/field members :deinit)))
    (try ((az/field members :put) 1 (az/init :void {})))
    (try ((az/field members :put) 2 (az/init :void {})))
    (try (testing/expect ((az/field members :contains) 2)))
    (try (testing/expect (ak/! ((az/field members :contains) 3))))
    (set! _ ((az/field members :remove) 2))
    (try (testing/expect (ak/! ((az/field members :contains) 2))))))
