(ns aguafria.zig.discovery-named-tuple-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defconst Tuple
  (az/struct [(az/tuple-field-decl :u8)
              (az/tuple-field-decl :u8)]))

(az/defconst Mixed
  (az/struct [(az/tuple-field-decl :u8)
              (az/tuple-field-decl :u64)
              (az/tuple-field-decl [:array 2 :i16])]))

(az/deftest never-run
  (let [tuple (k/as [5 6] Tuple)
        array (k/as tuple [:array 2 :u8])]
    (k/= :_ array))
  (k/unreachable))
