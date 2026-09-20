(ns learn.example.generic-data-structure
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- List :type [[T {:zig/prefix "comptime"} :type]]
  (az/struct
    [[:items [:slice T]]
     [:len :usize]]))

(az/defvar buffer [:array 10 :i32] ak/undefined)

(az/defvar values
  (az/init {:items (ak/& buffer) :len 0} (List :i32)))

(comment
  (List :i32))
