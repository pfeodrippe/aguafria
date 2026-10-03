(ns learn.example.test-missized-packed-struct
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/deftest missized-packed-struct
  (let [S (a/struct {:layout :packed, :type :u32}
                    [[:a :u16]
                     [:b :u8]])]
    (k/= :_ (S {:a 4 :b 2}))))

(comment
  (missized-packed-struct))
