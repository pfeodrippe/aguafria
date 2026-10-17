(ns learn.example.catch-err-return
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]
            [learn.example.error-union-parsing-u64 :as parsing]))

(a/defn- do-a-thing [:error-union :void] [[str [:slice :u8]]]
  (let [number (a/catch-capture [err] (parsing/parseU64 str 10)
                                (return err))]
    (= :_ number))) ; ...
