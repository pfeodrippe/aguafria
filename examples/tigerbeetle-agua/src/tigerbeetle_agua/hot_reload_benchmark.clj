(ns tigerbeetle-agua.hot-reload-benchmark
  "Reproducible hot-reload measurements over TigerBeetle's generated graph."
  (:require [aguafria.zig.benchmark :as benchmark]
            [clojure.walk :as walk]
            [tigerbeetle-agua.hot-reload-leaf :as leaf]
            [tigerbeetle-agua.hot-reload-target :as target]))

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
      (throw (ex-info "Benchmark could not find the expected TigerBeetle form"
                      {:declaration (:name descriptor)
                       :expected expected})))
    (assoc descriptor :body body)))

(defn- verified-value
  [label expected actual]
  (when-not (= expected actual)
    (throw (ex-info "Hot-reload behavior did not become observable"
                    {:label label :expected expected :actual actual})))
  {:value actual})

(defn- fresh-leaf-value
  [context]
  (+ 1000 (mod (:fresh-value context) 1000000)))

(defn- fresh-scale
  [context]
  (+ 3 (mod (:fresh-value context) 1000000)))

(defn simple!
  "Measure a stable-dispatch leaf edit through an already native caller."
  ([] (simple! 11))
  ([value]
   (let [_ (target/leaf-caller)]
     (benchmark/measure-edit!
      {:var #'leaf/leaf-value
       :project :tigerbeetle
       :complexity :simple
       :label "leaf through stable caller"
       :edit #(assoc % :body [value])
       :verify-change
       (fn []
         {:direct (verified-value "leaf direct change"
                                  value (leaf/leaf-value))
          :caller (verified-value "leaf caller change"
                                  value (target/leaf-caller))})
       :verify-restore
       (fn []
         {:direct (verified-value "leaf direct restore"
                                  10 (leaf/leaf-value))
          :caller (verified-value "leaf caller restore"
                                  10 (target/leaf-caller))})}))))

(defn medium!
  "Measure exact propagation from a comptime function to its native caller."
  []
  (let [_ (target/comptime-caller)]
    (benchmark/measure-edit!
     {:var #'leaf/comptime-scale
      :project :tigerbeetle
      :complexity :medium
      :label "comptime scalar caller"
      :edit #(replace-form % "(* value 2)" (constantly '(* value 3)))
      :verify-change
      #(verified-value "comptime caller change" 15 (target/comptime-caller))
      :verify-restore
      #(verified-value "comptime caller restore" 10 (target/comptime-caller))})))

(defn complex!
  "Measure a compatible method edit to TigerBeetle's real QueueType factory."
  ([] (complex! 1))
  ([identity-depth]
   (let [queue-size
         (requiring-resolve 'tigerbeetle-agua.hot-reload-queue-target/queue-size)
         queue-type (requiring-resolve 'tigerbeetle.src.queue/QueueType)
         expected (queue-size)]
     (benchmark/measure-edit!
      {:var queue-type
       :project :tigerbeetle
       :complexity :complex
       :label "real QueueType comptime method body"
       :edit #(replace-form
               %
               "(return (field (field self any) count))"
               (fn [form]
                 (list 'return
                       (nth (iterate (fn [value] (list '+ value 0))
                                     (second form))
                            identity-depth))))
       :verify-change
       #(verified-value "QueueType change" expected (queue-size))
       :verify-restore
       #(verified-value "QueueType restore" expected (queue-size))}))))

(defn simple-series!
  "Measure fresh leaf artifacts through the existing native caller."
  ([] (simple-series! 5))
  ([samples]
   (let [_ (target/leaf-caller)]
     (benchmark/measure-fresh-edits!
      {:var #'leaf/leaf-value
       :project :tigerbeetle
       :complexity :simple
       :label "fresh leaf through stable caller"
       :samples samples
       :edit (fn [declaration context]
               (assoc declaration :body [(fresh-leaf-value context)]))
       :verify-change
       (fn [context]
         (let [value (fresh-leaf-value context)]
           {:direct (verified-value "fresh leaf direct"
                                    value (leaf/leaf-value))
            :caller (verified-value "fresh leaf caller"
                                    value (target/leaf-caller))}))
       :verify-restore
       (fn []
         {:direct (verified-value "leaf direct series restore"
                                  10 (leaf/leaf-value))
          :caller (verified-value "leaf caller series restore"
                                  10 (target/leaf-caller))})}))))

(defn medium-series!
  "Measure fresh comptime scalar propagation into its live caller."
  ([] (medium-series! 5))
  ([samples]
   (let [_ (target/comptime-caller)]
     (benchmark/measure-fresh-edits!
      {:var #'leaf/comptime-scale
       :project :tigerbeetle
       :complexity :medium
       :label "fresh comptime scalar caller"
       :samples samples
       :edit
       (fn [declaration context]
         (let [scale (fresh-scale context)]
           (replace-form declaration "(* value 2)"
                         (constantly (list '* 'value scale)))))
       :verify-change
       (fn [context]
         (verified-value "fresh comptime caller"
                         (* 5 (fresh-scale context))
                         (target/comptime-caller)))
       :verify-restore
       #(verified-value "comptime caller series restore"
                        10 (target/comptime-caller))}))))

(defn complex-series!
  "Measure fresh QueueType comptime bodies and their concrete specialization."
  ([] (complex-series! 5))
  ([samples]
   ;; Materialize the generic and its concrete caller once; distribution
   ;; samples then all exercise the same steady-state propagation topology.
   (complex!)
   (let [queue-size
         (requiring-resolve 'tigerbeetle-agua.hot-reload-queue-target/queue-size)
         queue-type (requiring-resolve 'tigerbeetle.src.queue/QueueType)
         expected (queue-size)]
     (benchmark/measure-fresh-edits!
      {:var queue-type
       :project :tigerbeetle
       :complexity :complex
       :label "fresh QueueType comptime method body"
       :samples samples
       :edit
       (fn [declaration context]
         (replace-form
          declaration
          "(return (field (field self any) count))"
          (fn [form]
            (list 'return
                  (list '+ (second form)
                        (list '- (:fresh-value context)
                              (:fresh-value context)))))))
       :verify-change
       (fn [_]
         (verified-value "fresh QueueType" expected (queue-size)))
       :verify-restore
       #(verified-value "QueueType series restore" expected (queue-size))}))))

(defn run-distributions!
  "Run fresh simple/medium/complex TigerBeetle samples in one JVM."
  ([] (run-distributions! 5))
  ([samples]
   {:simple (benchmark/summary (simple-series! samples))
    :medium (benchmark/summary (medium-series! samples))
    :complex (benchmark/summary (complex-series! samples))}))

(defn run-all!
  "Run the three TigerBeetle cases and return compact EDN summaries."
  []
  {:simple (benchmark/summary (simple!))
   :medium (benchmark/summary (medium!))
   :complex (benchmark/summary (complex!))})
