(ns learn.examples.idiomatic-metaprogramming.bad-default-value
  "Converted from bad_default_value.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.std.log :as log]
            [aguafria.zig :as az]))

(az/defconst Threshold
  (az/container {:kind :struct}
    (az/field-decl :minimum :f32 0.25)
    (az/field-decl :maximum :f32 0.75)
    (az/const-decl Category
      (az/container {:kind :enum}
        (az/enum-field-decl :low)
        (az/enum-field-decl :medium)
        (az/enum-field-decl :high)))
    (az/fn-decl categorize :- Category [[threshold Threshold] [value :f32]]
      (debug/assert (>= (az/field threshold :maximum)
                        (az/field threshold :minimum)))
      (ak/return
        (if (< value (az/field threshold :minimum))
          :.low
          (if (> value (az/field threshold :maximum)) :.high :.medium))))))

(az/defn main [:error-union :void] []
  (let [^{:var Threshold} threshold {:maximum 0.20}
        category ((az/field threshold :categorize) 0.90)]
    (log/info "category: {t}" [category])))
