(ns learn.example.generic-data-structure
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- List :type [[T {:attrs #{k/comptime}} :type]]
  (az/struct
   [[:items [:slice T]]
    [:len :usize]]))

;; The generic List data structure can be instantiated by passing in a type:
(az/defvar buffer [:array 10 :i32] k/undefined)

(az/defvar list (az/init {:items (k/& buffer) :len 0} (List :i32)))

(comment
  (List :i32))
