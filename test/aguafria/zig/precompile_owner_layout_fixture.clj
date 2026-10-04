(ns aguafria.zig.precompile-owner-layout-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defconst address :u32 7)

(a/defconst size :u32 9)

(a/defconst alignment :u32 11)

(a/defconst __aguafria_jvm_address_0 :u32 13)

(a/defstruct PrivateRecord {:attrs #{}}
             [[:logical {:zig/name "slice"} :u32]
              [:value {:zig/name "@\"const\""} :u64]])

(a/deftest never-run
  (let [record (PrivateRecord {:slice 42 :const 9})]
    (k/= :_ (a/field record :slice))
    (k/= :_ (a/field record :const)))
  (k/unreachable))
