(ns aguafria.zig.precompile-parallel-test
  (:require [aguafria.zig.bundle :as bundle]
            [aguafria.zig.compiler-work :as work]
            [clojure.test :refer [deftest is]])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- artifacts [n]
  (into {} (for [i (range n)
                 :let [artifact {:module "fixture" :hash (str i) :validation-pending? true
                                 :command ["zig" "build-lib"]}]]
             [(#'bundle/artifact-id artifact) artifact])))

(deftest admission-parallelizes-and-isolates-rejections-with-monotonic-progress
  (let [collected (atom {:artifacts (artifacts 8)})
        active (atom 0) peak (atom 0) observations (atom []) progress (atom [])
        ready (CountDownLatch. 2)
        validate (fn [_ batch _ _]
                   (swap! peak max (swap! active inc))
                   (try
                     (swap! observations conj [(.isVirtual (Thread/currentThread)) work/*phase*])
                     (.countDown ready)
                     (when-not (.await ready 5 TimeUnit/SECONDS)
                       (throw (ex-info "Validation did not overlap" {})))
                     (Thread/sleep 10)
                     (not-any? #(= "3" (:hash %)) batch)
                     (finally (swap! active dec))))]
    (with-redefs-fn {#'bundle/candidate identity
                     #'bundle/validation-batch-limit 2
                     #'bundle/validate-batch! validate}
      (fn []
        (binding [work/*phase* :namespace-admission]
          (let [result (bundle/validate-pending!
                        "unused" collected
                        {:parallelism 2 :progress #(swap! progress conj %)
                         :validate-artifact (fn [_] {:status :failed :err "original diagnostic"})})]
            (is (= {:validated 7 :failed 1} (:statuses result)))
            (is (= 2 @peak))
            (is (every? #(= [true :namespace-admission] %) @observations))
            (is (= 1 (:individual-rechecks result)))
            (is (= (:compiler-batches result) (count (:batch-timings result))))
            (is (every? #(and (number? (:duration-ms %)) (not (neg? (:duration-ms %)))) (:batch-timings result)))
            (is (apply <= (map :resolved @progress)))
            (is (= 8 (:resolved (last @progress))))
            (is (= 1 (:failed (last @progress))))
            (is (every? #(<= (:active %) 2) @progress))
            (is (= 7 (count (:artifacts @collected))))
            (is (= ["original diagnostic"]
                   (keep :err (vals (:validation-results @collected)))))))))))

(deftest parallel-admission-infrastructure-error-does-not-publish-partial-results
  (let [collected (atom {:artifacts (artifacts 4)})
        before @collected ready (CountDownLatch. 2)]
    (with-redefs-fn
      {#'bundle/candidate identity
       #'bundle/validation-batch-limit 2
       #'bundle/validate-batch!
       (fn [_ batch _ _]
         (.countDown ready)
         (when-not (.await ready 5 TimeUnit/SECONDS)
           (throw (ex-info "Validation did not overlap" {})))
         (if (some #(= "0" (:hash %)) batch)
           (throw (ex-info "Compiler terminated" {:exit 137}))
           true))}
      (fn []
        (is (= 137 (try (bundle/validate-pending! "unused" collected {:parallelism 2})
                        nil
                        (catch clojure.lang.ExceptionInfo error (:exit (ex-data error))))))))
    (is (= before @collected))))

(deftest later-pack-failure-interrupts-an-earlier-slow-worker
  (let [ready (CountDownLatch. 2) interrupted (promise)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Later job failed"
         (#'bundle/parallel-mapv
          2 (fn [index]
              (.countDown ready)
              (when-not (.await ready 5 TimeUnit/SECONDS)
                (throw (ex-info "Workers did not overlap" {})))
              (if (= index 1)
                (throw (ex-info "Later job failed" {}))
                (try (Thread/sleep 10000)
                     (catch InterruptedException error
                       (deliver interrupted true)
                       (throw error)))))
          [0 1])))
    (is (= true (deref interrupted 1000 false)))))

(deftest independent-packs-overlap-but-publish-indexes-only-after-all-succeed
  (doseq [fail? [false true]]
    (let [collected (atom {:artifacts (update-vals (artifacts 2) #(assoc % :isolated-linkage? true))})
          ready (CountDownLatch. 2) published (atom []) progress (atom [])]
      (with-redefs-fn
        {#'bundle/candidate identity
         #'bundle/finish-compatible!
         (fn [_ local callbacks]
           (let [artifact (first (vals (:artifacts @local))) hash (:hash artifact)]
             (is (.isVirtual (Thread/currentThread)))
             (.countDown ready)
             (when-not (.await ready 5 TimeUnit/SECONDS)
               (throw (ex-info "Pack builds did not overlap" {})))
             (is (empty? @published))
             (when (and fail? (= hash "1"))
               (throw (ex-info "Pack failed" {})))
             ((:publish-index! callbacks) #(swap! published conj hash))
             {:packs [{:id hash}] :packed-handlers 1 :compiler-invocations 1 :reused-handlers 0}))}
        (fn []
          (if fail?
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Pack failed"
                                  (bundle/finish! "unused" collected {:parallelism 2})))
            (let [result (bundle/finish! "unused" collected {:parallelism 2 :progress #(swap! progress conj %)})]
              (is (= 2 (count (:packs result))))
              (is (= [0 1 2] (mapv :completed @progress)))))))
      (is (= (if fail? #{} #{"0" "1"}) (set @published))))))
