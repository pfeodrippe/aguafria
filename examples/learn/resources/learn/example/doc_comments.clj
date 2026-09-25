(ns learn.example.doc-comments
  (:require [aguafria.zig :as az]))

(az/defstruct Timestamp
  "A structure for storing a timestamp, with nanosecond precision
  (this is a multiline docstring)."
  [[:seconds
    {:doc "The number of seconds since the epoch (this is also documentation)."}
    :i64] ; signed so we can represent pre-1970 (not documentation)
   [:nanos
    {:doc "The number of nanoseconds past the second (documentation again)."}
    :u32]
   (az/fn unix-epoch Timestamp
     "Returns a Timestamp representing the Unix epoch:
      1970 Jan 1 00:00:00 UTC (this is documentation too)."
     []
     (Timestamp {:seconds 0 :nanos 0}))])
