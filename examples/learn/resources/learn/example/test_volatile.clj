(ns learn.example.test-volatile
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest volatile
  (let [mmio-ptr (as (ptrFromInt 0x12345678) [:* {:volatile? true} :u8])]
    (k/try (testing/expectEqual (a/type [:* {:volatile? true} :u8])
                                (TypeOf mmio-ptr)))))

(comment
  (volatile))
