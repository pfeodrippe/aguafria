(ns learn.fragments.error-trace-storage
  "Converted from stack_trace_struct.zig"
  (:require [aguafria.zig :as az]))

(az/defstruct StackTrace
  [[:index :usize]
   [:instruction_addresses [:array N :usize]]])
