(ns learn.snippet.stack-trace-struct
  (:require [aguafria.zig :as az]))

(az/defstruct StackTrace
  [[:index :usize]
   [:instruction_addresses [:array N :usize]]])
