(ns learn.example.single-value-error-set
  (:require [aguafria.zig :as az]))

(az/defconst err
  (:FileNotFound (az/type [:error-set [:FileNotFound]])))
