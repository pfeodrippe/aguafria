(ns learn.example.test-intCast-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest integer-cast-panic
  (let [a (k/var 0xabcd :u16)] ; runtime-known
    (k/= :_ (k/& a))
    (let [b (k/u8 (k/intCast a))]
      (k/= :_ b))))

(comment
  (integer-cast-panic))
