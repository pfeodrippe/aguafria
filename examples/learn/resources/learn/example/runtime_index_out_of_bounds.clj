(ns learn.example.runtime-index-out-of-bounds
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- foo :u8 [[x [:slice-const :u8]]]
  (az/get x 5))

(az/defn main :void []
  (let [x (foo "hello")]
    (k/= :_ x)))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
