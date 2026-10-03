(ns learn.example.generic-data-structure
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- List :type [[T {:attrs #{k/comptime}} :type]]
  (a/struct
   [[:items [:slice T]]
    [:len :usize]]))

;; The generic List data structure can be instantiated by passing in a type:
(a/defvar buffer [:array 10 :i32] k/undefined)

(a/defvar list (a/init {:items (k/& buffer) :len 0} (List :i32)))

(comment
  (List :i32))
