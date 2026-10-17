(ns learn.snippet.call-malloc-from-zig
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defextern malloc [:optional [:many :u8]] [[size :usize]])

(a/defn- do-a-thing [:optional [:* Foo]] []
  (let [memory (orelse (malloc 1234) (return nil))]
    ;; The successful allocation path is intentionally left unfinished.
    (= :_ memory)))
