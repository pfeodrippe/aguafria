(ns learn.snippet.stack-trace-struct
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defstruct StackTrace
  [[:index :usize]
   [:instruction_addresses [:array N :usize]]])
