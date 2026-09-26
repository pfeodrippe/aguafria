(ns learn.example.base64
  (:require [aguafria.keyword :as k]
            [aguafria.std.base64 :as base64]
            [aguafria.zig :as az]))

(az/defn decode-base-64 :usize
  {:attrs #{k/export}}
  [[dest-ptr [:many :u8]] [dest-len :usize]
   [source-ptr [:many-const :u8]] [source-len :usize]]
  (let [src (az/slice source-ptr 0 source-len)
        dest (az/slice dest-ptr 0 dest-len)
        base64-decoder (:Decoder base64/standard)
        decoded-size (catch ((:calcSizeForSlice base64-decoder) src)
                            (k/unreachable))]
    (catch ((:decode base64-decoder) (az/slice dest 0 decoded-size) src)
           (k/unreachable))
    decoded-size))

(comment
  (with-open [arena (java.lang.foreign.Arena/ofConfined)]
    (let [source-ptr (.allocateFrom arena "SGVsbG8=")
          dest-ptr (.allocate arena 6)]
      (decode-base-64 dest-ptr 6 source-ptr 8)
      (.getString dest-ptr 0))))
