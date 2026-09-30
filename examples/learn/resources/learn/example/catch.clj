(ns learn.example.catch
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]
            [learn.example.error-union-parsing-u64 :as parsing]))

(az/defn- do-a-thing :void [[str [:slice :u8]]]
  (let [number (catch (parsing/parseU64 str 10) 13)]
    (k/= :_ number))) ; ...
