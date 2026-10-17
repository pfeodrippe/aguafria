(ns aguafria.zig.discovery-named-tuple-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defconst Tuple
  (a/struct [(a/tuple-field-decl :u8)
             (a/tuple-field-decl :u8)]))

(a/defconst Mixed
  (a/struct [(a/tuple-field-decl :u8)
             (a/tuple-field-decl :u64)
             (a/tuple-field-decl [:array 2 :i16])]))

(a/deftest never-run
  (let [tuple (as [5 6] Tuple)
        array (as tuple [:array 2 :u8])]
    (= :_ array))
  (unreachable))
