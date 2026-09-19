(ns learn.examples.idiomatic-errors.comptime-unwrap-null
  "Converted from test_comptime_unwrap_null.zig"
  (:require [aguafria.zig :as az]))

(az/defcomptime reject-absent-number
  (let [^{:zig/type [:optional :i32]} optional-number nil
        number (az/unwrap optional-number)]
    (set! _ number)))
