(ns learn.example.generic-data-structure
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- List :type [[T {:attrs #{k/comptime}} :type]]
  (az/struct
    [[:items [:slice T]]
     [:len :usize]]))

(az/defvar buffer [:array 10 :i32] k/undefined)

(az/defvar values (az/init {:items (k/& buffer) :len 0} (List :i32)))

(comment
  (List :i32))
