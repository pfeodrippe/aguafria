(ns learn.example.try
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defimport parsing "error_union_parsing_u64.zig" [parseU64])

(az/defn- do-a-thing [:error-union :void] [[text [:slice :u8]]]
  ;; try propagates an error, or unwraps the successful number.
  (let [number (try (parsing/parseU64 text 10))]
    (ak/= :_ number)))
