(ns aguafria.zig.discovery-lexical-container-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn empty-name [:slice-const :u8] []
  (let [Foo (a/struct [])]
    (typeName Foo)))

(a/defn inline-name [:slice-const :u8] []
  (typeName (a/struct [])))

(a/defn aligned :usize []
  (let [S (a/struct [[:a {:align 2} :u32]
                     [:b {:align 64} :u32]])]
    (alignOf S)))

(a/defn increment-local :i32 []
  (let [S (a/struct [[:x {:var 1234} :i32]])]
    (+= (:x S) 1)
    (:x S)))

(a/deftest tag-members
  (let [Point (a/struct [[:x :u8] [:y :u8]])
        Item (a/union {:attrs #{enum}}
                      [[:a :u32] [:c Point] [:d :void] [:e :u32]])]
    (= :_ (:a Item))
    (= :_ (:e Item))
    (= :_ (:c Item))
    (= :_ (:d Item))))
