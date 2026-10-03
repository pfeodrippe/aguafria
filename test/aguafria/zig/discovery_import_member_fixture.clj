(ns aguafria.zig.discovery-import-member-fixture
  (:require [aguafria.zig :as a]))

(a/defimport ascii "std" [[is-digit "ascii.isDigit"]])

(a/defn decimal? :bool
  [[character :u8]]
  (ascii/is-digit character))
