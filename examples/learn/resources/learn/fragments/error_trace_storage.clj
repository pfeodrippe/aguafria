(ns learn.fragments.error-trace-storage
  (:require [aguafria.zig :as az]))

(az/defstruct StackTrace
  [[:index :usize]
   [:instruction_addresses [:array N :usize]]])
