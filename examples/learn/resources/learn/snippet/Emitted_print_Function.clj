(ns learn.snippet.Emitted-print-Function
  (:require [aguafria.zig :as az]))

(az/defn write-formatted [:error-union :void]
  [[writer [:* Writer]] [text [:slice-const :u8]] [number :i32]]
  (try ((az/field writer :write) "here is a string: '"))
  (try ((az/field writer :printValue) text))
  (try ((az/field writer :write) "' here is a number: "))
  (try ((az/field writer :printValue) number))
  (try ((az/field writer :write) "\n"))
  (try ((az/field writer :flush))))
