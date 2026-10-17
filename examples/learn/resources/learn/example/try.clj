(ns learn.example.try
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]
            [learn.example.error-union-parsing-u64 :as parsing]))

(a/defn- do-a-thing [:error-union :void] [[str [:slice :u8]]]
  (let [number (k/try (parsing/parseU64 str 10))]
    (= :_ number))) ; ...
