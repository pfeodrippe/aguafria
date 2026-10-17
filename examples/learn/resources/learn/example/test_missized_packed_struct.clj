(ns learn.example.test-missized-packed-struct
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/deftest missized-packed-struct
  (let [S (a/struct {:layout :packed, :type :u32}
                    [[:a :u16]
                     [:b :u8]])]
    (= :_ (S {:a 4 :b 2}))))

(comment
  (missized-packed-struct))
