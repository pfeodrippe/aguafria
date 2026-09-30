(ns aguafria.zig.discovery-import-member-fixture
  (:require [aguafria.zig :as az]))

(az/defimport ascii "std" [[is-digit "ascii.isDigit"]])

(az/defn decimal? :bool
  [[character :u8]]
  (ascii/is-digit character))
