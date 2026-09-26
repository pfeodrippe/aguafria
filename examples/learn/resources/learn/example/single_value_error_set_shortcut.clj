(ns learn.example.single-value-error-set-shortcut
  (:require [aguafria.zig :as az]))

(az/defconst err (az/error-value :FileNotFound))
