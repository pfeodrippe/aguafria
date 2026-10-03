(ns learn.example.handle-error-with-catch-block
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [learn.example.error-union-parsing-u64 :as parsing]))

(a/defn- do-a-thing :void [[str [:slice :u8]]]
  (let [number (catch (parsing/parseU64 str 10)
                      (a/with-block :blk
                     ;; do things
                        (k/break :blk 13)))]
    (k/= :_ number))) ; number is now initialized
