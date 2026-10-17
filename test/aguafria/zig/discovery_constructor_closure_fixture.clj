(ns aguafria.zig.discovery-constructor-closure-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defconst Text (a/type [:slice-const :u8]))

(a/defn text-length :usize
  [[text [:slice-const :u8]]]
  (:len text))

(a/defn aliased-text-length :usize
  [[text Text]]
  (:len text))

(a/defn flag-value :bool
  [[flag :bool]]
  flag)

(a/defn scratch-length :usize
  [[scratch [:* [:array 7 :i32]]]]
  (:len @scratch))

(a/defn local-scratch-length :usize
  []
  (let [scratch (k/var (as undefined [:array 7 :i32]))]
    (= :_ (k/& scratch))
    (:len scratch)))
