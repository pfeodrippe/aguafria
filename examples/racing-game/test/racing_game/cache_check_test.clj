(ns racing-game.cache-check-test
  (:require [clojure.test :refer [deftest is]]
            [aguafria.zig.precompile :as precompile]
            [racing-game.cache-check :as cache-check]))

(deftest whole-project-cache-acceptance-rejects-incomplete-coverage
  (let [namespaces (#'cache-check/selected-namespaces)
        analysis (-> (mapv #(hash-map :namespace %) namespaces)
                     (assoc-in [0 :operations]
                               [{:status :observed :handlers [{:status :prepared}]}])
                     (assoc-in [0 :functions]
                               [{:function 'fixture/call :status :prepared}]))
        report {:analysis analysis
                :coverage (precompile/coverage analysis)
                :scalar-constructor-profiles {:types [:u64] :statuses {:prepared 2}}}]
    (is (seq namespaces))
    (is (nil? (#'cache-check/check-coverage! report)))
    (doseq [incomplete [(update report :analysis pop)
                        (update report :analysis conj {:namespace 'missing.namespace})
                        (update report :analysis conj (first analysis))
                        (assoc-in report [:analysis 0 :baseline] {:exit 1})
                        (assoc-in report [:analysis 0 :operations 0 :status] :unobserved)
                        (assoc-in report [:analysis 0 :operations 0 :handlers 0 :status]
                                  :unsupported)
                        (assoc-in report [:analysis 0 :functions 0 :status] :failed)
                        (assoc-in report [:coverage :namespaces :attempted] 0)
                        (assoc-in report [:coverage :namespaces :baseline-failures] 1)
                        (assoc-in report [:coverage :namespaces :statuses] {:analysis-failed 1})
                        (assoc-in report [:coverage :runtime-candidates :fully-prepared] 0)
                        (assoc-in report [:coverage :declared-functions :statuses] {:failed 1})
                        (assoc-in report [:scalar-constructor-profiles :types] [])
                        (assoc-in report [:scalar-constructor-profiles :statuses] {})
                        (assoc-in report [:scalar-constructor-profiles :statuses] {:failed 1})]]
      (is (thrown? AssertionError (#'cache-check/check-coverage! incomplete))))))
