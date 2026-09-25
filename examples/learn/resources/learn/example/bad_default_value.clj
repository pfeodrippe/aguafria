(ns learn.example.bad-default-value
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.std.log :as log]
            [aguafria.zig :as az]))

(az/defstruct Threshold
  [[:minimum {:default 0.25} :f32]
   [:maximum {:default 0.75} :f32]
   [:Category {:const (az/enum [:low :medium :high])} :type]
   (az/fn- categorize Category [[threshold Threshold] [value :f32]]
     (debug/assert (>= (az/field threshold :maximum)
                       (az/field threshold :minimum)))
     (if (< value (az/field threshold :minimum))
       :.low
       (if (> value (az/field threshold :maximum)) :.high :.medium)))])

(az/defn main [:error-union :void] []
  (let [threshold (ak/var (Threshold {:maximum 0.20}))
        category ((az/field threshold :categorize) 0.90)]
    (log/info "category: {t}" [category])))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
