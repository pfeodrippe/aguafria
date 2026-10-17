(ns learn.example.base64
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.base64 :as base64]
            [aguafria.zig :as a]
            [clojure.core :as c]))

(a/defn decode-base-64 :usize
  {:attrs #{export}}
  [[dest-ptr [:many :u8]] [dest-len :usize]
   [source-ptr [:many-const :u8]] [source-len :usize]]
  (let [src (a/slice source-ptr 0 source-len)
        dest (a/slice dest-ptr 0 dest-len)
        base64-decoder (:Decoder base64/standard)
        decoded-size (k/catch ((:calcSizeForSlice base64-decoder) src)
                              (unreachable))]
    (k/catch ((:decode base64-decoder) (a/slice dest 0 decoded-size) src)
             (unreachable))
    decoded-size))

(comment
  (c/with-open [arena (java.lang.foreign.Arena/ofConfined)]
    (let [source-ptr (.allocateFrom arena "SGVsbG8=")
          dest-ptr (.allocate arena 6)]
      (decode-base-64 dest-ptr 6 source-ptr 8)
      (.getString dest-ptr 0))))
