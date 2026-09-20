(ns learn.example.test-unhandled-enumeration-value
  (:require [aguafria.zig :as az]))

(az/defenum Color
  [:auto
   :off
   :on])

(az/deftest missing-enum-prong-test
  (let [color (az/field Color :off)]
    (az/switch-stmt color
      (case [(az/field Color :auto)] (az/block))
      (case [(az/field Color :on)] (az/block)))))

(comment
  (missing-enum-prong-test))
