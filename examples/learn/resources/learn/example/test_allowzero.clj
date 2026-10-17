(ns learn.example.test-allowzero
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest allowzero
  (let [zero (k/var 0 :usize)] ; var to make to runtime-known
    (= :_ (k/& zero)) ; suppress 'var is never mutated' error
    (let [ptr (as (ptrFromInt zero) [:* {:allowzero? true} :i32])]
      (k/try (testing/expectEqual 0 (intFromPtr ptr))))))

(comment
  (allowzero))
