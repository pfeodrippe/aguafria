(ns learn.snippet.stack-trace-struct
  (:require [aguafria.zig :as a]))

(a/defstruct StackTrace
  [[:index :usize]
   [:instruction_addresses [:array N :usize]]])
