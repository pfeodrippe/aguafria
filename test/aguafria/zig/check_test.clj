(ns aguafria.zig.check-test
  (:require [aguafria.zig.check :as check]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(defn- run-path []
  (str (.resolve (Files/createTempDirectory "aguafria-check-test-"
                                            (make-array java.nio.file.attribute.FileAttribute 0)) "run")))

(deftest checker-validates-before-starting-workers
  (is (thrown? clojure.lang.ExceptionInfo (check/check! {})))
  (is (thrown? clojure.lang.ExceptionInfo
               (check/check! {:scenarios [{:id :x :call 'unqualified :expect {:value 1}}]})))
  (is (thrown? clojure.lang.ExceptionInfo
               (check/check! {:tests '[a/b] :compilation-modes [:prepared :prepared]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":execution-modes has been removed"
                        (check/check! {:tests '[a/b] :execution-modes [:jvm-body]}))))

(deftest native-functions-and-tests-across-compilation-modes
  (let [path (run-path)
        report (check/check! {:report-dir path :timeout-seconds 180
                              :tests '[aguafria.zig.check-fixture/succeeds]
                              :scenarios [{:id :public-private
                                           :call 'aguafria.zig.check-fixture/entry
                                           :args [42] :expect {:value 43}}]})]
    (is (:accepted? report) (pr-str (map #(select-keys % [:status :reason :failure]) (:results report))))
    (is (= :aguafria-check-v2 (:schema report)))
    (is (= 4 (count (:results report))))
    (is (every? :consistent? (:comparisons report)))
    (doseq [r (:results report)]
      (is (zero? (get-in r [:worker :exit])))
      (is (not (contains? r :execution)))
      (when (= :prepared (:compilation r))
        (is (empty? (:evaluation-compiler-attempts r)))
        (is (empty? (:forbidden-evaluation-events r)))
        (is (not-any? #(= :evaluate (:stage %)) (:final-compiler-attempts r)))
        (is (empty? (:final-native-events r)))))
    (is (= report (edn/read-string (slurp (io/file path "report.edn")))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exists"
                          (check/check! {:report-dir path :tests '[aguafria.zig.check-fixture/succeeds]})))))

(deftest actual-native-test-failure-is-not-accepted
  (let [r (check/check! {:report-dir (run-path) :compilation-modes [:on-demand]
                         :tests '[aguafria.zig.check-fixture/fails]})]
    (is (false? (:accepted? r)))
    (is (= :failed (-> r :results first :status)))
    (is (some? (-> r :results first :failure)))))

(deftest strict-prepared-rejects-caught-attempts-and-fallback-events
  (let [r (check/check! {:report-dir (run-path) :compilation-modes [:prepared]
                         :scenarios [{:id :caught :call 'aguafria.zig.check-fixture/caught-attempt
                                      :args [] :expect {:value false}}
                                     {:id :thread :call 'aguafria.zig.check-fixture/caught-thread-attempt
                                      :args [] :expect {:value false}}
                                     {:id :fallback :call 'aguafria.zig.check-fixture/forbidden-fallback
                                      :args [] :expect {:value :returned}}]})
        [caught thread fallback] (:results r)]
    (is (:accepted? (:preparation r)))
    (is (false? (:accepted? r)))
    (is (= "false" (get-in caught [:observation :value])))
    (is (= :failed (:status caught)))
    (is (= 1 (count (:evaluation-compiler-attempts caught))))
    (is (= "false" (get-in thread [:observation :value])))
    (is (= :failed (:status thread)))
    (is (= 1 (count (:evaluation-compiler-attempts thread))))
    (is (= :failed (:status fallback)))
    (is (= [:disk-cache-hit] (mapv :event (:forbidden-evaluation-events fallback))))
    (is (= [{:stage :evaluate :event :disk-cache-hit}] (:final-native-events fallback)))))
