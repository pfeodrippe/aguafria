(ns learn.example.test-string-literal-to-const-slice
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn foo :void
  [[s [:slice-const :u8]]]
  (k/= :_ s))

(az/deftest string-literal-to-constant-slice
  (foo "hello"))

(comment
  (string-literal-to-constant-slice))
