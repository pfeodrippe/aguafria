(ns aguafria.zig.discovery-private-nested-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defstruct Owner
  [(a/const-decl Hidden {:attrs #{}}
                 (a/struct
                  [[:min :u32]
                   [:max :u32]
                   (a/fn- increment :u32
                          [[value :u32]]
                          (k/+ value 1))
                   (a/fn- contains :bool
                          [[self Hidden] [value :u32]]
                          (and (k/<= (:min self) value)
                               (k/<= value (:max self))))
                   (a/fn- unspecialized :u32
                          [[value :anytype]]
                          (k/+ value 3))]))
   (a/const-decl Codes {:attrs #{}}
                 (a/enum {:type :u8}
                         [[:zero 0]
                          (a/fn- twice :u32
                                 [[value :u32]]
                                 (k/* value 2))]))
   (a/const-decl Cells {:attrs #{}}
                 (a/union
                  [[:integer :u32]
                   [:float :f32]
                   (a/fn- subtract :u32
                          [[value :u32]]
                          (k/- value 1))]))])

(a/defn create-hidden (:Hidden Owner)
  [[minimum :u32] [maximum :u32]]
  (a/init {:min minimum :max maximum} (:Hidden Owner)))
