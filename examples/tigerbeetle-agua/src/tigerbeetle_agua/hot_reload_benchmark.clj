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

(defn simple!
  "Measure a stable-dispatch leaf edit through an already native caller."
  []
  (let [_ (target/leaf-caller)]
    (benchmark/measure-edit!
     {:var #'leaf/leaf-value
      :project :tigerbeetle
      :complexity :simple
      :label "leaf through stable caller"
      :edit #(assoc % :body [11])
      :verify-change (fn [] {:direct (leaf/leaf-value)
                             :caller (target/leaf-caller)})
      :verify-restore (fn [] {:direct (leaf/leaf-value)
                              :caller (target/leaf-caller)})})))

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
      :verify-change (fn [] {:value (target/comptime-caller)})
      :verify-restore (fn [] {:value (target/comptime-caller)})})))

(defn complex!
  "Measure a compatible method edit to TigerBeetle's real QueueType factory."
  []
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
                (list 'return (list '+ (second form) 0))))
      :verify-change (fn [] {:size (queue-size)
                             :unchanged? (= expected (queue-size))})
      :verify-restore (fn [] {:size (queue-size)
                              :unchanged? (= expected (queue-size))})})))

(defn run-all!
  "Run the three TigerBeetle cases and return compact EDN summaries."
  []
  {:simple (benchmark/summary (simple!))
   :medium (benchmark/summary (medium!))
   :complex (benchmark/summary (complex!))})
