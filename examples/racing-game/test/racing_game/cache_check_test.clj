(ns racing-game.cache-check-test
  (:require [clojure.test :refer [deftest is]]
            [racing-game.cache-check :as cache-check]))

(deftest whole-project-cache-acceptance-rejects-incomplete-coverage
  (let [namespaces (#'cache-check/selected-namespaces)
        report {:analysis (mapv #(hash-map :namespace %) namespaces)
                :coverage {:namespaces {:attempted (count namespaces)
                                        :baseline-failures 0
                                        :statuses {:analyzed (count namespaces)}}
                           :runtime-candidates {:total 1 :fully-prepared 1}
                           :declared-functions {:total 1 :statuses {:prepared 1}}}
                :scalar-constructor-profiles {:types [:u64] :statuses {:prepared 2}}}]
    (is (seq namespaces))
    (is (nil? (#'cache-check/check-coverage! report)))
    (doseq [incomplete [(update report :analysis pop)
                        (update report :analysis conj {:namespace 'missing.namespace})
                        (assoc-in report [:coverage :namespaces :attempted] 0)
                        (assoc-in report [:coverage :namespaces :baseline-failures] 1)
                        (assoc-in report [:coverage :namespaces :statuses] {:analysis-failed 1})
                        (assoc-in report [:coverage :runtime-candidates :fully-prepared] 0)
                        (assoc-in report [:coverage :declared-functions :statuses] {:failed 1})
                        (assoc-in report [:scalar-constructor-profiles :types] [])
                        (assoc-in report [:scalar-constructor-profiles :statuses] {:failed 1})]]
      (is (thrown? AssertionError (#'cache-check/check-coverage! incomplete))))))
