(ns learn.snippet.call-malloc-from-zig
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defextern malloc [:optional [:many :u8]] [[size :usize]])

(a/defn- do-a-thing [:optional [:* Foo]] []
  (let [memory (orelse (malloc 1234) (k/return nil))]
    ;; The successful allocation path is intentionally left unfinished.
    (k/= :_ memory)))
