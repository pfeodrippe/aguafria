(ns learn.example.doc-comments
  (:require [aguafria.zig :as az]))

(az/defstruct Timestamp
  "A structure for storing a timestamp, with nanosecond precision (this is a
multiline doc comment)."
  [[:seconds
    {:doc "The number of seconds since the epoch (this is also a doc comment)."}
    :i64] ; signed so we can represent pre-1970 (not a doc comment)
   [:nanos
    {:doc "The number of nanoseconds past the second (doc comment again)."}
    :u32]
   (az/fn unix-epoch Timestamp
     "Returns a `Timestamp` struct representing the Unix epoch; that is, the
moment of 1970 Jan 1 00:00:00 UTC (this is a doc comment too)."
     []
     (Timestamp {:seconds 0 :nanos 0}))])
