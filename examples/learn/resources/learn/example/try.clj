(ns learn.example.try
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defimport parsing "error_union_parsing_u64.zig" [parseU64])

(az/defn- do-a-thing [:error-union :void] [[str [:slice :u8]]]
  (let [number (try (parsing/parseU64 str 10))]
    (k/= :_ number))) ; ...
