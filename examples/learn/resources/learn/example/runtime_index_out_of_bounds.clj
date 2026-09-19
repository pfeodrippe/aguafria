(ns learn.example.runtime-index-out-of-bounds
  (:require [aguafria.zig :as az]))

(az/defn- sixth-byte :u8 [[text [:slice-const :u8]]]
  (az/index text 5))

(az/defn main :void []
  (let [byte (sixth-byte "hello")]
    (set! _ byte)))
