(ns learn.snippet.call-malloc-from-zig
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defextern malloc [:optional [:many :u8]] [[size :usize]])

(az/defn- do-a-thing [:optional [:* Foo]] []
  (let [memory (orelse (malloc 1234) (k/return nil))]
    ;; The successful allocation path is intentionally left unfinished.
    (k/= :_ memory)))
