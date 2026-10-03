(ns learn.example.try
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [learn.example.error-union-parsing-u64 :as parsing]))

(a/defn- do-a-thing [:error-union :void] [[str [:slice :u8]]]
  (let [number (try (parsing/parseU64 str 10))]
    (k/= :_ number))) ; ...
