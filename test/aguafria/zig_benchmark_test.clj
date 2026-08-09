(ns aguafria.zig-benchmark-test
  (:require [aguafria.zig :as az]
            [aguafria.zig.benchmark :as benchmark]
            [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is testing]]))

(def original-declaration
  {:module "benchmark.fixture"
   :name 'leaf
   :kind :fn
   :implementation-fingerprint "original"})

(deftest fresh-edit-series-excludes-cache-backed-attempts
  (testing "only never-built artifacts enter the latency distribution"
    (let [publication-count (atom 0)
          verified (atom [])
          restored (atom 0)]
      (with-redefs [benchmark/declaration (constantly original-declaration)
                    runtime/declaration-info identity
                    benchmark/measure-publication!
                    (fn [{:keys [declaration verify]}]
                      (let [publication (swap! publication-count inc)
                            restore? (= "original"
                                        (:implementation-fingerprint
                                         declaration))]
                        (when verify (verify))
                        (if restore?
                          (do
                            (swap! restored inc)
                            {:artifact-freshness :cache-backed
                             :observable-ms 5.0
                             :compiler-ms 0.0})
                          {:artifact-freshness
                           (if (= publication 1) :cache-backed :fresh)
                           :observable-ms (* 10.0 publication)
                           :compiler-ms (* 4.0 publication)})))]
        (let [result
              (benchmark/measure-fresh-edits!
               {:var 'benchmark.fixture/leaf
                :label "fresh fixture"
                :samples 2
                :edit (fn [declaration context]
                        (assoc declaration
                               :implementation-fingerprint
                               (:fresh-id context)))
                :verify-change #(swap! verified conj %)
                :verify-restore #(swap! restored inc)})]
          (is (= 1 (:excluded-attempt-count result)))
          (is (= [2 1]
                 (mapv #(get-in % [:fresh-context :attempt])
                       (:samples result))))
          (is (= [1 2]
                 (mapv #(get-in % [:fresh-context :sample-number])
                       (:samples result))))
          (is (= 20.0 (get-in result [:distribution :p50 :observable-ms])))
          (is (= 30.0 (get-in result [:distribution :p95 :observable-ms])))
          (is (= 3 (count @verified)))
          (is (= 2 @restored))
          (is (= 2 (count (:samples (benchmark/summary result))))))))))

(deftest fresh-edit-series-rejects-a-repeated-fingerprint
  (testing "a caller cannot accidentally benchmark the same edit twice"
    (let [publication-count (atom 0)]
      (with-redefs [benchmark/declaration (constantly original-declaration)
                    runtime/declaration-info identity
                    benchmark/measure-publication!
                    (fn [_]
                      (swap! publication-count inc)
                      {:artifact-freshness :fresh})]
        (let [error
              (try
                (benchmark/measure-fresh-edits!
                 {:var 'benchmark.fixture/leaf
                  :samples 2
                  :edit (fn [declaration _]
                          (assoc declaration
                                 :implementation-fingerprint "same-change"))})
                nil
                (catch clojure.lang.ExceptionInfo error error))]
          (is (= :hot-reload-fresh-edit
                 (:aguafria/phase (ex-data error))))
          ;; One changed publication plus the single final restoration.
          (is (= 2 @publication-count)))))))

(deftest fresh-edit-series-measures-real-native-publications
  (testing "unique contexts produce fresh, behaviorally verified dylibs"
    (let [old-config (az/configuration)
          module-symbol
          (symbol (str "aguafria.benchmark-native-" (random-uuid)))
          target-ns (create-ns module-symbol)]
      (try
        (az/configure! {:async? false :modules {}})
        (binding [*ns* target-ns]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (eval '(az/defn leaf :- :i32 [] 1)))
        (let [leaf-var (ns-resolve target-ns 'leaf)
              expected (fn [context]
                         (+ 100 (mod (:fresh-value context) 1000000)))
              result
              (benchmark/measure-fresh-edits!
               {:var leaf-var
                :label "native fresh fixture"
                :samples 2
                :edit (fn [declaration context]
                        (assoc declaration :body [(expected context)]))
                :verify-change
                (fn [context]
                  (let [actual ((var-get leaf-var))
                        expected-value (expected context)]
                    (when-not (= expected-value actual)
                      (throw
                       (ex-info "Fresh native behavior was not observable"
                                {:expected expected-value :actual actual})))
                    actual))
                :verify-restore #(when-not (= 1 ((var-get leaf-var)))
                                   (throw
                                    (ex-info "Original behavior was not restored"
                                             {})))})]
          (is (= 2 (count (:samples result))))
          (is (every? #(= :fresh (:artifact-freshness %))
                      (:samples result)))
          (is (= 2 (count (set (map :verification (:samples result))))))
          (is (pos? (get-in result
                            [:distribution :p95 :observable-ms])))
          (is (= 1 ((var-get leaf-var)))))
        (finally
          (az/configure! old-config)
          (remove-ns module-symbol))))))
