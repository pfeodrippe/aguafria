(ns learn.example.bad-default-value
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.log :as log]
            [aguafria.zig :as a]))

(a/defstruct Threshold
  [[:minimum {:default 0.25} :f32]
   [:maximum {:default 0.75} :f32]
   [:Category {:const (a/enum [:low :medium :high])} :type]
   (a/fn- categorize Category [[t Threshold] [value :f32]]
          (let [{:keys [minimum maximum]} t]
            (debug/assert (k/>= maximum minimum))
            (if (k/< value minimum)
              :.low
              (if (k/> value maximum) :.high :.medium))))])

(a/defn main [:error-union :void] []
  (let [threshold (k/var (Threshold {:maximum 0.20}))
        category ((:categorize threshold) 0.90)]
    (log/info "category: {t}" [category])))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
