(ns learn.example.test-volatile
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest volatile
  (let [mmio-ptr (k/as (k/ptrFromInt 0x12345678) [:* {:volatile? true} :u8])]
    (try (testing/expectEqual (a/type [:* {:volatile? true} :u8])
                              (k/TypeOf mmio-ptr)))))

(comment
  (volatile))
