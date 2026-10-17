(ns learn.example.export-any-symbol-name
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn a-function-name-that-is-a-complete-sentence :void
  {:attrs #{export}
   :zig/name "@\"A function name that is a complete sentence.\""}
  [])

(comment
  (a-function-name-that-is-a-complete-sentence))
