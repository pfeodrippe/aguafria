(ns learn.examples.idiomatic-values.doc-comments
  "Converted from doc_comments.zig"
  (:require [aguafria.zig :as az]))

(az/defconst Timestamp
  "A structure for storing a timestamp, with nanosecond precision
  (this is a multiline docstring)."
  (az/container {:kind :struct}
    (az/field-decl :seconds
      "The number of seconds since the epoch (this is also documentation)."
      :i64) ; signed so we can represent pre-1970 (not documentation)
    (az/field-decl :nanos
      "The number of nanoseconds past the second (documentation again)."
      :u32)
    (az/fn-decl unix-epoch
      "Returns a Timestamp representing the Unix epoch:
      1970 Jan 1 00:00:00 UTC (this is documentation too)."
      {:attrs #{:public}}
      :- Timestamp
      []
      (az/init Timestamp {:seconds 0 :nanos 0}))))
