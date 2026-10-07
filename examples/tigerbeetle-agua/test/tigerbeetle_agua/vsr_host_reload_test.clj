(ns tigerbeetle-agua.vsr-host-reload-test
  "Bounded live-host proof for the actual VSR sector_floor declaration."
  (:require [aguafria.keyword :as k]
            [aguafria.std.Io :as std-io]
            [aguafria.std.Io.Duration :as duration]
            [aguafria.std.process :as std-process]
            [aguafria.zig :as a]
            [aguafria.zig.benchmark :as benchmark]
            [aguafria.zig.host :as host]
            [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is]]
            [tigerbeetle.src.vsr :as vsr]))

;; Only this test's state is shared with its native host. All cross-thread
;; accesses use the same compiler-checked atomic operations.
(a/defvar running :bool false)
(a/defvar observed :u64 0)
(a/defvar ticks :u64 0)

(a/defn reset-state! :void []
  (k/atomicStore :u64 (k/& observed) 0 :.release)
  (k/atomicStore :u64 (k/& ticks) 0 :.release)
  (k/atomicStore :bool (k/& running) true :.release))

(a/defn stop! :void []
  (k/atomicStore :bool (k/& running) false :.release))

(a/defn observed-value :u64 []
  (k/atomicLoad :u64 (k/& observed) :.acquire))

(a/defn tick-value :u64 []
  (k/atomicLoad :u64 (k/& ticks) :.acquire))

(a/defn main :!void {:attrs #{:public}} [[process-init std-process/Init]]
  (while (k/atomicLoad :bool (k/& running) :.acquire)
    (k/atomicStore :u64 (k/& observed) (vsr/sector_floor 4097) :.release)
    (set! _ (k/atomicRmw :u64 (k/& ticks) :.Add 1 :.release))
    (try (std-io/sleep (:io process-init) (duration/fromMilliseconds 1) :.awake))))

(defonce last-gate (atom nil))

(defn- scalar [value]
  (try (a/value value)
       (finally (a/close! value))))

(defn- addresses []
  (into {} (map (fn [[name value]]
                  [name (.address (a/native-segment value))]))
        [[:running running] [:observed observed] [:ticks ticks]]))

(defn- await-observed [expected previous-tick]
  (let [deadline (+ (System/nanoTime) 10000000000)]
    (loop []
      (let [state {:value (scalar (observed-value))
                   :ticks (scalar (tick-value))}]
        (cond
          (and (= expected (:value state)) (> (:ticks state) previous-tick)) state
          (< (System/nanoTime) deadline) (do (Thread/sleep 10) (recur))
          :else (throw (ex-info "VSR host did not observe the publication"
                                {:expected expected :previous-tick previous-tick
                                 :state state})))))))

(defn- offset-result [descriptor delta]
  (let [body (:body descriptor)
        result (last body)]
    (when-not (and (seq? result)
                   (contains? #{'return 'aguafria.keyword/return} (first result)))
      (throw (ex-info "VSR sector_floor final return changed shape" {:body body})))
    (assoc descriptor :body
           (conj (vec (butlast body))
                 (with-meta (list (first result)
                                  (list 'aguafria.keyword/+ (second result) delta))
                   (meta result))))))

(deftest actual-vsr-callee-publications-retain-native-host-and-atomic-state
  (let [original (runtime/declaration-info (benchmark/declaration #'vsr/sector_floor))
        old-config (a/configuration)
        pid (.pid (java.lang.ProcessHandle/current))
        live-host (atom nil)
        observations (atom [])
        publication-reports (atom [])
        baseline-fingerprint (atom nil)
        previous-tick (atom -1)]
    (reset! last-gate nil)
    (try
      (a/configure! {:async? false})
      (reset-state!)
      (reset! live-host (host/start! #'main [] {:argv0 "tiger-vsr-owned-reload"}))
      (let [initial-addresses (addresses)
            host-id (:id (host/info @live-host))
            verify
            (fn [label expected]
              (let [state (assoc (await-observed expected @previous-tick)
                                 :label label :pid (.pid (java.lang.ProcessHandle/current))
                                 :host (host/info @live-host) :addresses (addresses)
                                 :direct (with-open [offset (k/u64 4097)]
                                           (scalar (vsr/sector_floor offset))))]
                (reset! previous-tick (:ticks state))
                (swap! observations conj state)
                (is (= expected (:value state)))
                (is (= expected (:direct state)))
                (is (= pid (:pid state)))
                (is (= host-id (get-in state [:host :id])))
                (is (true? (get-in state [:host :active?])))
                (is (= initial-addresses (:addresses state)))
                state))]
        (is (every? pos? (vals initial-addresses)))
        (verify :original 4096)
        ;; Native materialization enriches dependency identities. Compare the
        ;; restored registered implementation with that live baseline, not a
        ;; descriptor captured before the original call had materialized.
        (reset! baseline-fingerprint
                (:implementation-fingerprint
                 (first (filter #(= (:declaration-key original) (:declaration-key %))
                                (runtime/registered-declarations "tigerbeetle.src.vsr")))))
        (doseq [delta [4096 8192]]
          (swap! publication-reports conj
                 (benchmark/summary
                  (benchmark/measure-edit!
                   {:var #'vsr/sector_floor :project :tigerbeetle
                    :complexity :vsr-sector-native-host
                    :label (str "actual sector_floor native host +" delta)
                    :edit #(offset-result % delta)
                    :verify-change #(verify [:changed delta] (+ 4096 delta))
                    :verify-restore #(verify [:restored delta] 4096)}))))
        (is (string? @baseline-fingerprint))
        (is (= @baseline-fingerprint
               (:implementation-fingerprint
                (first (filter #(= (:declaration-key original) (:declaration-key %))
                               (runtime/registered-declarations "tigerbeetle.src.vsr"))))))
        (is (= (:body original)
               (:body (first (filter #(= (:declaration-key original) (:declaration-key %))
                                     (runtime/registered-declarations "tigerbeetle.src.vsr"))))))
        (stop!)
        (let [exit (host/await! @live-host)]
          (is (= 0 (:exit-code exit)))
          (is (false? (:active? (host/info @live-host))))
          (is (apply < (map :ticks @observations)))
          (reset! last-gate {:pid pid :scope :actual-vsr-sector-floor-only
                             :observations @observations
                             :publications @publication-reports
                             :baseline-implementation-fingerprint @baseline-fingerprint
                             :restored? true :exit exit})))
      (finally
        (when (and @live-host (:active? (host/info @live-host)))
          (stop!)
          (host/await! @live-host))
        (a/configure! old-config)))))
