(ns aguafria.zig.discovery-lexical-value-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest contextual-construction
  (let [S (a/struct [[:x :u32]])
        input (k/u64 123)
        value (S {:x (k/intCast input)})]
    (try (testing/expectEqual (k/as 123 :u32) (:x value)))))

(a/deftest aligned-fields
  (let [S (a/struct [[:a {:align 2} :u32] [:b {:align 64} :u32]])
        value (k/var (S {:a 1 :b 2}))]
    (try (testing/expectEqual 64 (k/alignOf S)))
    (try (testing/expectEqual (a/type [:* {:align 2} :u32]) (k/TypeOf (k/& (:a value)))))
    (try (testing/expectEqual (a/type [:* {:align 64} :u32]) (k/TypeOf (k/& (:b value)))))))

(a/deftest packed-struct
  (let [S (a/struct {:layout :packed} [[:a :u4] [:b :u4]])
        left (S {:a 1 :b 2})
        right (S {:b 2 :a 1})]
    (try (testing/expectEqual left right))))

(a/deftest packed-union
  (let [U (a/union {:layout :packed} [[:a :u4] [:b :i4]])
        left (U {:a 3})
        right (U {:b 3})]
    (try (testing/expectEqual left right))))

(a/deftest pointer-capture
  (let [Point (a/struct [[:x :u8] [:y :u8]])
        Item (a/union {:attrs #{k/enum}} [[:a :u32] [:c Point] [:d :void] [:e :u32]])
        value (k/var (Item {:c (Point {:x 1 :y 2})}))
        result (k/switch value
                        (case [(:a Item) (:e Item)] [item] item)
                        (case [(:c Item)] [(a/pointer-capture item)]
                              (a/with-block :blk
                                (k/+= (:x @item) 1)
                                (k/break :blk 6)))
                        (case [(:d Item)] 8))]
    (try (testing/expectEqual 6 result))
    (try (testing/expectEqual 2 (a/get-in value [:c :x])))))
