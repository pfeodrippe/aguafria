(ns learn.example.export-any-symbol-name
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn a-function-name-that-is-a-complete-sentence :void
  {:attrs #{k/export}
   :zig/name "@\"A function name that is a complete sentence.\""}
  [])

(comment
  (a-function-name-that-is-a-complete-sentence))
