(ns learn.example.test-missized-packed-struct
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest missized-packed-struct-test
  ;; Intentionally rejected: the fields total 24 bits, but the backing integer has 32.
  (let [WrongSize (az/struct {:layout :packed, :argument :u32}
                    [[:a :u16]
                     [:b :u8]])]
    (ak/= :_ (az/init {:a 4 :b 2} WrongSize))))

(comment
  (missized-packed-struct-test))
