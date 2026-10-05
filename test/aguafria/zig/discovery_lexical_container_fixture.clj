(ns aguafria.zig.discovery-lexical-container-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn empty-name [:slice-const :u8] []
  (let [Foo (a/struct [])]
    (k/typeName Foo)))

(a/defn inline-name [:slice-const :u8] []
  (k/typeName (a/struct [])))

(a/defn aligned :usize []
  (let [S (a/struct [[:a {:align 2} :u32]
                    [:b {:align 64} :u32]])]
    (k/alignOf S)))

(a/defn increment-local :i32 []
  (let [S (a/struct [[:x {:var 1234} :i32]])]
    (k/+= (:x S) 1)
    (:x S)))

(a/deftest tag-members
  (let [Point (a/struct [[:x :u8] [:y :u8]])
        Item (a/union {:attrs #{k/enum}}
                      [[:a :u32] [:c Point] [:d :void] [:e :u32]])]
    (k/= :_ (:a Item))
    (k/= :_ (:e Item))
    (k/= :_ (:c Item))
    (k/= :_ (:d Item))))
