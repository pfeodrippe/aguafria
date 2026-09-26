(ns learn.example.test-missized-packed-struct
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest missized-packed-struct
  (let [S (az/struct {:layout :packed, :argument :u32}
                     [[:a :u16]
                      [:b :u8]])]
    (k/= :_ (S {:a 4 :b 2}))))

(comment
  (missized-packed-struct))
