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

(defn run-all!
  "Run the three TigerBeetle cases and return compact EDN summaries."
  []
  {:simple (benchmark/summary (simple!))
   :medium (benchmark/summary (medium!))
   :complex (benchmark/summary (complex!))})
