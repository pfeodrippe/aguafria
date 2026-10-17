(ns learn.example.test-intCast-builtin
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/deftest integer-cast-panic
  (let [a (k/var 0xabcd :u16)] ; runtime-known
    (= :_ (k/& a))
    (let [b (u8 (intCast a))]
      (= :_ b))))

(comment
  (integer-cast-panic))
