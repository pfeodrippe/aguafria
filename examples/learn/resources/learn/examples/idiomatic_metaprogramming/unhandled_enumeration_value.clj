(ns learn.examples.idiomatic-metaprogramming.unhandled-enumeration-value
  (:require [aguafria.zig :as az]))

(az/defconst Color
  (az/container {:kind :enum}
    (az/enum-field-decl :auto)
    (az/enum-field-decl :off)
    (az/enum-field-decl :on)))

(az/deftest missing-enum-prong-test
  (let [color (az/field Color :off)]
    (az/switch-stmt color
      (case [(az/field Color :auto)] (az/block))
      (case [(az/field Color :on)] (az/block)))))
