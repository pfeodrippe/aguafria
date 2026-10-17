(ns aguafria.zig.discovery-import-member-fixture
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defimport ascii "std" [[is-digit "ascii.isDigit"]])

(a/defn decimal? :bool
  [[character :u8]]
  (ascii/is-digit character))
