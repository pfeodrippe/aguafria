(ns aguafria.zig.check-configuration-fixture
  (:require [aguafria.zig :as a]))

;; Capture during loading, not merely when the scenario is invoked.
(def configured-at-load (select-keys (a/configuration) [:module-cache-tokens]))

(defn loaded-configuration [] configured-at-load)
