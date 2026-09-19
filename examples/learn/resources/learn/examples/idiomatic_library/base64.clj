(ns learn.examples.idiomatic-library.base64
  (:require [aguafria.keyword :as ak]
            [aguafria.std.base64 :as base64]
            [aguafria.zig :as az]))

(az/defn decode-base-64 :usize
  {:attrs #{:export}}
  [[destination [:many :u8]] [destination-length :usize]
   [source [:many-const :u8]] [source-length :usize]]
  (let [input (az/slice source 0 source-length)
        output (az/slice destination 0 destination-length)
        decoder (az/field base64/standard :Decoder)
        decoded-length (catch ((az/field decoder :calcSizeForSlice) input)
                         (ak/unreachable))]
    (catch ((az/field decoder :decode) (az/slice output 0 decoded-length) input)
      (ak/unreachable))
    decoded-length))
