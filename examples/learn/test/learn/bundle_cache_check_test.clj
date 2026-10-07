(ns learn.bundle-cache-check-test
  (:require [aguafria.zig.jvm :as jvm]
            [clojure.test :refer [deftest is testing]]
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
  (is (= 37 (count check/checked-lessons)))
  (is (= 80 check/expected-body-count))
  (is (= check/expected-body-count
         (reduce + (map #(count (:bodies (#'check/read-lesson %)))
                        check/checked-lessons))))
  (is (= 3 (count check/checked-constants)))
  (is (= 12 (count check/checked-native-owners))))

(deftest a-discarded-native-assertion-error-fails-the-body
  (with-redefs [jvm/invoke-reference! (fn [_ _] {:error {:name "TestExpectedEqual"}})]
    (let [result (#'check/evaluate-body
                  {:form '(do (aguafria.zig.jvm/invoke-reference!
                               {:symbol 'aguafria.std.testing/expectEqual} [1 2])
                              nil)})]
      (is (= :failed (:status result)))
      (is (= ["Native testing expectation failed during JVM replay"] (:causes result))))))

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
           ["missing constant" (assoc valid-report :expected-constant-count 1
                                      :constant-results [])]
           ["failed constant" (assoc valid-report :expected-constant-count 1
                                     :constant-results [{:status :failed}])]
           ["missing native owner" (assoc valid-report :expected-native-owner-count 1
                                          :native-owner-results [])]
           ["failed native owner" (assoc valid-report :expected-native-owner-count 1
                                         :native-owner-results [{:status :failed}])]
           ["standalone helper" (assoc valid-report :standalone [{:module "aguafria.jvm.x"}])]
           ["wrong pack" (assoc valid-report :bundles #{"another"})]
           ["no pack" (assoc valid-report :bundles #{})]
           ["two packs" (assoc valid-report :bundles #{"producer" "another"})]
           ["missing producer" (dissoc valid-report :producer-bundle-id)]
           ["already loaded" (assoc-in valid-report [:events :bundle-loaded] 0)]]]
    (testing label
      (is (thrown? clojure.lang.ExceptionInfo (check/validate-report! report))))))
