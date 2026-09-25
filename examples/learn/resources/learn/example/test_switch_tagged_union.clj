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
                 (case [(az/field Item :a) (az/field Item :e)] [value] value)
                 (case [(az/field Item :c)] [(az/pointer-capture point)]
                   (az/labeled-block updated
                     (k/+= (az/field @point :x) 1)
                     (k/break updated 6)))
                 (case [(az/field Item :d)] 8))]
    (try (testing/expectEqual 6 result))
    (try (testing/expectEqual 2 (az/field (az/field item :c) :x)))))

(comment
  (tagged-union-payload-capture-test))
