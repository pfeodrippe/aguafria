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

(defn- verified-value
  [label expected actual address]
  (let [same-state? (= address (live-address))]
    (when-not (and (= expected actual) same-state?)
      (throw (ex-info "Hot-reload behavior did not become observable"
                      {:label label
                       :expected expected
                       :actual actual
                       :same-state? same-state?})))
    {:value actual
     :same-state? same-state?}))

(defn simple!
  "Measure a compatible leaf edit while native state stays allocated."
  ([] (simple! 200))
  ([offset]
   (core/reset-session!)
   (let [address (live-address)]
     (benchmark/measure-edit!
      {:var #'live/display-offset
       :project :lightpanda
       :complexity :simple
       :label "display leaf with live state"
       :edit #(assoc % :body [offset])
       :verify-change
       (fn []
         (verified-value "display-offset change"
                         offset (live/displayed-value) address))
       :verify-restore
       (fn []
         (verified-value "display-offset restore"
                         100 (live/displayed-value) address))}))))

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
      (fn []
        (verified-value "parseFrameId change"
                        2 (core/parse-frame-id "FID-42") address))
      :verify-restore
      (fn []
        (verified-value "parseFrameId restore"
                        42 (core/parse-frame-id "FID-42") address))})))

(defn complex!
  "Measure a method edit inside Lightpanda's real Incrementing type factory."
  ([] (complex! 2))
  ([increment]
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
                            (constantly
                             (list 'aguafria.keyword/+% 'counter increment)))
       :verify-change
       (fn []
         (verified-value "Incrementing change"
                         increment (live/counter-next!) address))
       :verify-restore
       (fn []
         (verified-value "Incrementing restore"
                         (inc increment) (live/counter-next!) address))}))))

(defn run-all!
  "Run all Lightpanda reload levels and return compact timing summaries."
  []
  {:simple (benchmark/summary (simple!))
   :medium (benchmark/summary (medium!))
   :complex (benchmark/summary (complex!))})
