(ns learn.example.runtime-index-out-of-bounds
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn- foo :u8 [[x [:slice-const :u8]]]
  (a/get x 5))

(a/defn main :void []
  (let [x (foo "hello")]
    (= :_ x)))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
