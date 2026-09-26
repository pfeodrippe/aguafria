(ns learn.example.catch-err-return
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defimport parsing "error_union_parsing_u64.zig" [parseU64])

(az/defn- do-a-thing [:error-union :void] [[str [:slice :u8]]]
  (let [number (az/catch-capture [err] (parsing/parseU64 str 10)
                                 (k/return err))]
    (k/= :_ number))) ; ...
