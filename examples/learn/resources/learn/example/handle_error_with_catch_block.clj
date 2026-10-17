(ns learn.example.handle-error-with-catch-block
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]
            [learn.example.error-union-parsing-u64 :as parsing]))

(a/defn- do-a-thing :void [[str [:slice :u8]]]
  (let [number (k/catch (parsing/parseU64 str 10)
                        (a/with-block :blk
                     ;; do things
                          (break :blk 13)))]
    (= :_ number))) ; number is now initialized
