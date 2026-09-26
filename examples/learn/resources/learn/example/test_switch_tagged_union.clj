(ns learn.example.test-switch-tagged-union
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest tagged-union-payload-capture-test
  (let [Point (az/struct
                [[:x :u8]
                 [:y :u8]])
        Item (az/union {:attrs #{k/enum}}
               [[:a :u32]
                [:c Point]
                [:d :void]
                [:e :u32]])
        item (k/var (Item {:c (Point {:x 1 :y 2})}))
        result (k/switch item
                 ;; Matching fields with the same payload type can share a prong.
                 (case [(:a Item) (:e Item)] [value] value)
                 (case [(:c Item)] [(az/pointer-capture point)]
                   (az/with-block :updated
                     (k/+= (:x @point) 1)
                     (k/break :updated 6)))
                 (case [(:d Item)] 8))]
    (try (testing/expectEqual 6 result))
    (try (testing/expectEqual 2 (az/get-in item [:c :x])))))

(comment
  (tagged-union-payload-capture-test))
