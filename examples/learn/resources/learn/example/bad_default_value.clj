(ns learn.example.bad-default-value
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.log :as log]
            [aguafria.zig :as az]))

(az/defstruct Threshold
  [[:minimum {:default 0.25} :f32]
   [:maximum {:default 0.75} :f32]
   [:Category {:const (az/enum [:low :medium :high])} :type]
   (az/fn- categorize Category [[threshold Threshold] [value :f32]]
           (let [{:keys [minimum maximum]} threshold]
             (debug/assert (k/>= maximum minimum))
             (if (k/< value minimum)
               :.low
               (if (k/> value maximum) :.high :.medium))))])

(az/defn main [:error-union :void] []
  (let [threshold (k/var (Threshold {:maximum 0.20}))
        category ((:categorize threshold) 0.90)]
    (log/info "category: {t}" [category])))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
