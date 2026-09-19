(ns learn.examples.idiomatic-safety.runtime-index-out-of-bounds
  "Converted from runtime_index_out_of_bounds.zig"
  (:require [aguafria.zig :as az]))

(az/defn- sixth-byte :u8 [[text [:slice-const :u8]]]
  (az/index text 5))

(az/defn main :void []
  (let [byte (sixth-byte "hello")]
    (set! _ byte)))
