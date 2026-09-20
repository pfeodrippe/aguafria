(ns learn.example.runtime-index-out-of-bounds
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- foo :u8 [[text [:slice-const :u8]]]
  (az/index text 5))

(az/defn main :void []
  (let [byte (foo "hello")]
    (ak/= :_ byte)))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
