(ns learn.example.runtime-index-out-of-bounds
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- foo :u8 [[text [:slice-const :u8]]]
  (az/get text 5))

(az/defn main :void []
  (let [byte (foo "hello")]
    (k/= :_ byte)))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
