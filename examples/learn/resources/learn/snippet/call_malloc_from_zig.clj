(ns learn.snippet.call-malloc-from-zig
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defextern malloc [:optional [:many :u8]] [[size :usize]])

(az/defn- do-a-thing [:optional [:* Foo]] []
  (let [memory (orelse (malloc 1234) (ak/return nil))]
    ;; The successful allocation path is intentionally left unfinished.
    (ak/= :_ memory)))
