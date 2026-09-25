(ns learn.example.catch-err-return
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defimport parsing "error_union_parsing_u64.zig" [parseU64])

(az/defn- do-a-thing [:error-union :void] [[text [:slice :u8]]]
  ;; Capture the error and explicitly propagate it to the caller.
  (let [number (az/catch-capture [error] (parsing/parseU64 text 10)
                                 (k/return error))]
    (k/= :_ number)))
