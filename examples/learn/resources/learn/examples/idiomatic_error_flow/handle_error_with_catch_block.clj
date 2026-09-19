(ns learn.examples.idiomatic-error-flow.handle-error-with-catch-block
  (:require [aguafria.zig :as az]))

(az/defimport parsing "error_union_parsing_u64.zig" [parseU64])

(az/defn- do-a-thing :void [[text [:slice :u8]]]
  (let [number (catch (parsing/parseU64 text 10)
                 (az/labeled-block fallback
                   ;; Recovery work can run before yielding the fallback.
                   (break fallback 13)))]
    (set! _ number)))
