(ns learn.bundle-cache-check-test
  (:require [clojure.test :refer [deftest is testing]]
            [learn.bundle-cache-check :as check]))

(deftest authored-bodies-keep-scope-and-source-order
  (let [forms '[(ns example)
                (a/defconst n 3)
                (a/deftest example "Test documentation" {:attrs #{k/pub}}
                  (let [x 1] (expect x))
                  (expect 2))
                (comment (example))]
        bodies (check/test-bodies forms)]
    (is (= ['example 'example] (mapv :declaration bodies)))
    (is (= [0 1] (mapv :index bodies)))
    (is (= '[(let [x 1] (expect x)) (expect 2)] (mapv :form bodies)))))

(deftest main-bodies-preserve-enclosing-bindings
  (let [forms '[(a/defn helper :u32 [] 1)
                (a/defn main :void {:attrs #{k/pub}} []
                  (let [pair (helper)] (print-pair pair))
                  (check-result))]
        bodies (check/main-bodies forms)]
    (is (= ['main] (mapv :declaration bodies)))
    (is (= '[(do (let [pair (helper)] (print-pair pair)) (check-result))]
           (mapv :form bodies)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (check/main-bodies '[(a/defn main :void [[input :u32]] input)])))))

(deftest checked-source-inventory-has-every-expected-body
  (is (= 26 (count check/checked-lessons)))
  (is (= 56 (reduce + (map #(count (:bodies (#'check/read-lesson %)))
                           check/checked-lessons)))))

(def valid-report
  {:producer-bundle-id "producer"
   :expected-body-count 1
   :bundles #{"producer"}
   :compiled []
   :standalone []
   :events {:bundle-loaded 1 :bundle-cache-hit 2}
   :results [{:bodies [{:status :passed :result nil}]}]})

(deftest bundle-acceptance-fails-closed
  (is (= valid-report (check/validate-report! valid-report)))
  (doseq [[label report]
          [["no bodies" (assoc valid-report :results [])]
           ["missing body" (assoc valid-report :expected-body-count 2)]
           ["failed body" (assoc valid-report :results
                                 [{:bodies [{:status :failed :result {:error :Mismatch}}]}])]
           ["returned error without an exception"
            (assoc valid-report :results
                   [{:bodies [{:status :passed :result {:error :Mismatch}}]}])]
           ["native build" (assoc valid-report :compiled [{:event :compiled}])]
           ["failed build" (assoc valid-report :compiled [{:event :compile-failed}])]
           ["standalone helper" (assoc valid-report :standalone [{:module "aguafria.jvm.x"}])]
           ["wrong pack" (assoc valid-report :bundles #{"another"})]
           ["no pack" (assoc valid-report :bundles #{})]
           ["two packs" (assoc valid-report :bundles #{"producer" "another"})]
           ["missing producer" (dissoc valid-report :producer-bundle-id)]
           ["already loaded" (assoc-in valid-report [:events :bundle-loaded] 0)]]]
    (testing label
      (is (thrown? clojure.lang.ExceptionInfo (check/validate-report! report))))))
