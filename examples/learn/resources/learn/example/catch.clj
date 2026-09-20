(ns learn.example.catch
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defimport parsing "error_union_parsing_u64.zig" [parseU64])

(az/defn- do-a-thing :void [[text [:slice :u8]]]
  ;; A fallback turns either parse failure into an ordinary value.
  (let [number (catch (parsing/parseU64 text 10) 13)]
    (ak/= :_ number)))
