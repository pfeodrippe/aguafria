(ns learn.example.catch-err-return
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]
            [learn.example.error-union-parsing-u64 :as parsing]))

(az/defn- do-a-thing [:error-union :void] [[str [:slice :u8]]]
  (let [number (az/catch-capture [err] (parsing/parseU64 str 10)
                                 (k/return err))]
    (k/= :_ number))) ; ...
