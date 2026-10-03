(ns learn.example.runtime-index-out-of-bounds
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- foo :u8 [[x [:slice-const :u8]]]
  (a/get x 5))

(a/defn main :void []
  (let [x (foo "hello")]
    (k/= :_ x)))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
