(ns learn.example.generic-data-structure
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- ListOf :type [[T {:zig/prefix "comptime"} :type]]
  (az/container {:kind :struct}
    (az/field-decl :items [:slice T])
    (az/field-decl :len :usize)))

(az/defvar buffer [:array 10 :i32] ak/undefined)

(az/defvar values
  (az/init (ListOf :i32) {:items (ak/& buffer) :len 0}))

(comment
  (ListOf :i32))
