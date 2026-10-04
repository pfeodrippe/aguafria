(ns tigerbeetle-agua.cache-check-test
  (:require [clojure.test :refer [deftest is]]
            [aguafria.zig.precompile :as precompile]
            [tigerbeetle-agua.cache-check :as cache-check]))

(deftest cache-acceptance-rejects-incomplete-or-inconsistent-coverage
  (let [namespaces @#'cache-check/selected-namespaces
        functions [{:status :prepared}
                   {:status :skipped :reason :specialization}]
        analysis (mapv #(cond-> {:namespace % :baseline {:exit 0}}
                          (= % 'tigerbeetle-agua.hot-reload-leaf)
                          (assoc :functions functions
                                 :operations [{:status :observed
                                               :handlers [{:status :prepared}]}]))
                       namespaces)
        report {:analysis analysis
                :coverage (precompile/coverage analysis)
                :scalar-constructor-profiles {:types [:u64] :statuses {:prepared 2}}}]
    (is (= 5 (count namespaces)))
    (is (nil? (#'cache-check/check-coverage! report)))
    (doseq [incomplete [(update report :analysis pop)
                        (update report :analysis conj {:namespace 'missing.namespace})
                        (update report :analysis conj (first (:analysis report)))
                        (assoc-in report [:coverage :namespaces :attempted] 0)
                        (assoc-in report [:coverage :namespaces :baseline-failures] 1)
                        (assoc-in report [:coverage :namespaces :statuses] {:analysis-failed 5})
                        (assoc-in report [:coverage :runtime-candidates :fully-prepared] 0)
                        (assoc-in report [:coverage :declared-functions :total] 0)
                        (assoc-in report [:coverage :declared-functions :statuses] {:failed 2})
                        (update report :analysis
                                #(mapv (fn [entry]
                                         (update entry :functions
                                                 (fn [items]
                                                   (mapv (fn [item]
                                                           (assoc item :status :failed))
                                                         items))))
                                       %))
                        (update report :analysis
                                #(mapv (fn [entry]
                                         (update entry :functions
                                                 (fn [items]
                                                   (mapv (fn [item]
                                                           (assoc item :reason :unknown))
                                                         items))))
                                       %))
                        (assoc-in report [:scalar-constructor-profiles :types] [])
                        (assoc-in report [:scalar-constructor-profiles :statuses] {})
                        (assoc-in report [:scalar-constructor-profiles :statuses] {:failed 1})]]
      (is (thrown? AssertionError (#'cache-check/check-coverage! incomplete))))))
