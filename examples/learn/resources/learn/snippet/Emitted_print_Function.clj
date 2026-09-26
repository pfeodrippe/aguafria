(ns learn.snippet.Emitted-print-Function
  (:require [aguafria.zig :as az]))

(az/defn write-formatted [:error-union :void]
  [[writer [:* Writer]] [text [:slice-const :u8]] [number :i32]]
  (try ((:write writer) "here is a string: '"))
  (try ((:printValue writer) text))
  (try ((:write writer) "' here is a number: "))
  (try ((:printValue writer) number))
  (try ((:write writer) "\n"))
  (try ((:flush writer))))
