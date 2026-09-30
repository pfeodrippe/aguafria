(ns learn.example.try
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]
            [learn.example.error-union-parsing-u64 :as parsing]))

(az/defn- do-a-thing [:error-union :void] [[str [:slice :u8]]]
  (let [number (try (parsing/parseU64 str 10))]
    (k/= :_ number))) ; ...
