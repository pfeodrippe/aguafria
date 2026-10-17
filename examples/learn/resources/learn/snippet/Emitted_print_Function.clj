(ns learn.snippet.Emitted-print-Function
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn write-formatted [:error-union :void]
  [[writer [:* Writer]] [text [:slice-const :u8]] [number :i32]]
  (k/try ((:write writer) "here is a string: '"))
  (k/try ((:printValue writer) text))
  (k/try ((:write writer) "' here is a number: "))
  (k/try ((:printValue writer) number))
  (k/try ((:write writer) "\n"))
  (k/try ((:flush writer))))
