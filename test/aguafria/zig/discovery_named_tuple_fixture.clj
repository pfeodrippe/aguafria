(ns aguafria.zig.discovery-named-tuple-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defconst Tuple
  (a/struct [(a/tuple-field-decl :u8)
              (a/tuple-field-decl :u8)]))

(a/defconst Mixed
  (a/struct [(a/tuple-field-decl :u8)
              (a/tuple-field-decl :u64)
              (a/tuple-field-decl [:array 2 :i16])]))

(a/deftest never-run
  (let [tuple (k/as [5 6] Tuple)
        array (k/as tuple [:array 2 :u8])]
    (k/= :_ array))
  (k/unreachable))
