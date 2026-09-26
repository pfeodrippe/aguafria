(ns learn.example.test-switch-tagged-union
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest switch-on-tagged-union
  (let [Point (az/struct
               [[:x :u8]
                [:y :u8]])
        Item (az/union {:attrs #{k/enum}}
                       [[:a :u32]
                        [:c Point]
                        [:d :void]
                        [:e :u32]])
        a (k/var (Item {:c (Point {:x 1 :y 2})}))
        ;; Switching on more complex enums is allowed.
        b (k/switch a
                 ;; A capture group is allowed on a match, and will return the enum
                 ;; value matched. If the payload types of both cases are the same
                 ;; they can be put into the same switch prong.
                    (case [(:a Item) (:e Item)] [item] item)
                 ;; A reference to the matched value can be obtained using `*` syntax.
                    (case [(:c Item)] [(az/pointer-capture item)]
                          (az/with-block :blk
                            (k/+= (:x @item) 1)
                            (k/break :blk 6)))
                 ;; No else is required if the types cases was exhaustively handled
                    (case [(:d Item)] 8))]
    (try (testing/expectEqual 6 b))
    (try (testing/expectEqual 2 (az/get-in a [:c :x])))))

(comment
  (switch-on-tagged-union))
