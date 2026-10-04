(ns aguafria.zig.discovery-constructor-closure-fixture
  (:require [aguafria.keyword :as k]
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
  (let [scratch (k/var (k/as k/undefined [:array 7 :i32]))]
    (k/= :_ (k/& scratch))
    (:len scratch)))
