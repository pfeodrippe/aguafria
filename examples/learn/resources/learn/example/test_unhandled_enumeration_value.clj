(ns learn.example.test-unhandled-enumeration-value
  (:require [aguafria.zig :as az]))

(az/defenum Color
  [:auto
   :off
   :on])

(az/deftest exhaustive-switching
  (let [color (:off Color)]
    (az/switch-stmt color
                    (case [(:auto Color)] (az/block))
                    (case [(:on Color)] (az/block)))))

(comment
  (exhaustive-switching))
