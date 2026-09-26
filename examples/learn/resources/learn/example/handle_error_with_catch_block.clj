(ns learn.example.handle-error-with-catch-block
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defimport parsing "error_union_parsing_u64.zig" [parseU64])

(az/defn- do-a-thing :void [[str [:slice :u8]]]
  (let [number (catch (parsing/parseU64 str 10)
                      (az/with-block :blk
                     ;; do things
                        (k/break :blk 13)))]
    (k/= :_ number))) ; number is now initialized
