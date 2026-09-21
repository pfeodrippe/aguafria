(ns racing-game.native-evaluation
  "Paired policy evaluation through current native Granite, without physics or UI."
  (:require [aguafria.zig :as az]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [racing-game.inference :as inference]
            [racing-game.model :as model]
            [racing-game.protocol :as protocol])
  (:import [java.lang.foreign Arena MemorySegment]
           [java.util.concurrent Callable Executors ExecutorService]))

(def output-directory "build/jev-evaluation")
(def concurrency 4)
(defonce progress (atom {:completed 0}))

(defn read-edn [name]
  (edn/read-string (slurp (io/file output-directory name))))

(defn write-edn! [name data]
  (let [file (io/file output-directory name)]
    (io/make-parents file)
    (spit file (pr-str data))))

(defn- with-model [f]
  (when-let [summary (some-> (find-ns 'racing-game.worker) (ns-resolve 'summary))]
    (when (:started (az/value (summary)))
      (throw (ex-info "Native evaluation requires stopped game workers" {}))))
  (model/verify-assets!)
  (with-open [arena (Arena/ofConfined)]
    (try
      (when-not (:valid (az/value (inference/load-model!
                                   (.allocateFrom arena (str (model/model-file))))))
        (throw (ex-info "Native model failed to load" {})))
      (when-not (:valid (az/value (inference/load-action-head!
                                   (.allocateFrom arena (str (model/action-head-file))))))
        (throw (ex-info "Driver head failed to load" {})))
      (when-not (inference/initialize-sequences!)
        (throw (ex-info "Native sequence allocation failed" {})))
      (f)
      (finally
        (inference/free-sequences!)
        (inference/unload-action-head!)
        (inference/unload-model!)))))

(defn native-one [slot case]
  (with-open [arena (Arena/ofConfined)]
    (let [prompt (.getBytes ^String (:state case) "US-ASCII")
          memory (.allocate arena (alength prompt) 1)
          _ (.copyFrom memory (MemorySegment/ofArray prompt))
          started (System/nanoTime)
          result (az/value (inference/forward-compact-prompt!
                            slot memory (alength prompt) true))
          elapsed (/ (- (System/nanoTime) started) 1e6)
          action (- (:best_token result) 32)
          row {:id (:id case) :suite (:suite case) :action action
               :valid (and (:valid result)
                           (= (:candidate_count result) (if (= :team (:kind case)) 3 8))
                           (<= 0 action (if (= :team (:kind case)) 2 7)))
               :candidate-count (:candidate_count result) :latency-ms elapsed}]
      (swap! progress #(assoc % :completed (inc (:completed % 0)) :last-id (:id case)))
      row)))

(defn run-native!
  ([] (run-native! (read-edn "cases.edn") "granite.edn"))
  ([cases output-name]
   (reset! progress {:completed 0 :total (+ 2 (count cases))})
   (with-model
     (fn []
       (with-open [arena (Arena/ofConfined)]
         (when-not (:valid (az/value (inference/load-team-head!
                                     (.allocateFrom arena (str (model/team-head-file))))))
           (throw (ex-info "Team head failed to load" {})))
         (let [pool (Executors/newFixedThreadPool concurrency)
               ;; Plain executor threads inherit process output. Clojure futures
               ;; propagate nREPL writers, causing process-wide output capture
               ;; to serialize native calls and distort concurrency/latency.
               submit (fn [slot case]
                        (.submit ^ExecutorService pool
                                 ^Callable (reify Callable
                                             (call [_] (native-one slot case)))))
               team-offset (az/value protocol/racer-count)]
           (try
             (let [warmups (mapv (fn [kind]
                                  (.get (submit (if (= kind :team) team-offset 0)
                                                (first (filter #(= kind (:kind %)) cases)))))
                                [:driver :team])
                   results
                   (vec (mapcat
                         (fn [kind]
                           (mapcat
                            (fn [batch]
                              (let [jobs (mapv (fn [slot case]
                                                 (submit (+ slot (if (= kind :team) team-offset 0)) case))
                                               (range) batch)
                                    ;; Join every job before freeing shared model
                                    ;; state, even when one worker fails.
                                    settled (mapv #(try {:row (.get %)}
                                                        (catch Throwable e {:error e})) jobs)]
                                (when-let [error (some :error settled)] (throw error))
                                (mapv :row settled)))
                            (partition-all concurrency (filter #(= kind (:kind %)) cases))))
                         [:driver :team]))]
               (write-edn! output-name {:warmups warmups :concurrency concurrency
                                       :native-output :process-streams :results results})
               {:completed (count results)})
             (finally
               (.shutdown ^ExecutorService pool)
               (inference/unload-team-head!)))))))))
