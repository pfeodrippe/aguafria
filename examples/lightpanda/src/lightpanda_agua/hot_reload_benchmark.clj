(ns lightpanda-agua.hot-reload-benchmark
  "Reproducible one-JVM hot-reload checks over generated Lightpanda code."
  (:require [aguafria.zig :as az]
            [aguafria.zig.benchmark :as benchmark]
            [clojure.walk :as walk]
            [lightpanda-agua.core :as core]
            [lightpanda-agua.live :as live]))

(defn- replace-form
  [descriptor expected replacement]
  (let [replaced? (atom false)
        body (walk/postwalk
              (fn [form]
                (if (and (not @replaced?) (= expected (pr-str form)))
                  (do (reset! replaced? true) (replacement form))
                  form))
              (:body descriptor))]
    (when-not @replaced?
      (throw (ex-info "Expected generated Lightpanda form was not found"
                      {:declaration (:name descriptor)
                       :expected expected})))
    (assoc descriptor :body body)))

(defn- live-address
  []
  (live/session-address))

(defn simple!
  "Measure a compatible leaf edit while native state stays allocated."
  []
  (core/reset-session!)
  (let [address (live-address)]
    (benchmark/measure-edit!
     {:var #'live/display-offset
      :project :lightpanda
      :complexity :simple
      :label "display leaf with live state"
      :edit #(assoc % :body [200])
      :verify-change
      (fn [] {:displayed (live/displayed-value)
              :same-state? (= address (live-address))})
      :verify-restore
      (fn [] {:displayed (live/displayed-value)
              :same-state? (= address (live-address))})})))

(defn medium!
  "Measure a body edit to Lightpanda's converted frame-id parser."
  []
  (let [parse-frame-id
        (requiring-resolve 'lightpanda.src.cdp.id/parseFrameId)
        address (live-address)]
    (when-not (= 42 (core/parse-frame-id "FID-42"))
      (throw (ex-info "Unexpected baseline frame id" {})))
    (benchmark/measure-edit!
     {:var parse-frame-id
      :project :lightpanda
     :complexity :medium
     :label "converted frame-id parser"
     :edit #(replace-form %
                           "(slice input 4)"
                           (constantly '(slice input 5)))
      :verify-change
      (fn [] {:value (core/parse-frame-id "FID-42")
              :same-state? (= address (live-address))})
      :verify-restore
      (fn [] {:value (core/parse-frame-id "FID-42")
              :same-state? (= address (live-address))})})))

(defn complex!
  "Measure a method edit inside Lightpanda's real Incrementing type factory."
  []
  (core/reset-session!)
  (let [incrementing
        (requiring-resolve 'lightpanda.src.cdp.id/Incrementing)
        address (live-address)]
    (benchmark/measure-edit!
     {:var incrementing
      :project :lightpanda
     :complexity :complex
     :label "real Incrementing comptime method"
     :edit #(replace-form %
                           "(aguafria.keyword/+% counter 1)"
                           (constantly '(aguafria.keyword/+% counter 2)))
      :verify-change
      (fn [] {:next (live/counter-next!)
              :same-state? (= address (live-address))})
      :verify-restore
      (fn [] {:next (live/counter-next!)
              :same-state? (= address (live-address))})})))

(defn run-all!
  "Run all Lightpanda reload levels and return compact timing summaries."
  []
  {:simple (benchmark/summary (simple!))
   :medium (benchmark/summary (medium!))
   :complex (benchmark/summary (complex!))})
