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
  (doseq [exception [nil {} {:stage :load :type 'clojure.lang.ExceptionInfo}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (check/check! {:scenarios [{:id :bad-exception :call 'clojure.core/identity
                                            :expect {:exception exception}}]}))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":execution-modes has been removed"
                        (check/check! {:tests '[a/b] :execution-modes [:jvm-body]}))))

(deftest expected-exceptions-match-diagnostics-and-stage-without-bypassing-audit
  (let [directory (str (Files/createTempDirectory "aguafria-check-negative-"
                                                  (make-array java.nio.file.attribute.FileAttribute 0)))
        load-file (io/file directory "negative.clj")
        policy-file (io/file directory "policy.clj")
        fallback-file (io/file directory "fallback.clj")
        exception {:stage :load :type 'clojure.lang.ExceptionInfo
                   :message-pattern "^intended diagnostic$" :data {:lesson :negative}}
        scenario {:id :load :call 'clojure.core/identity :args [42]
                  :load-files [(str load-file)] :expect {:exception exception}}]
    (spit load-file "(throw (ex-info \"intended diagnostic\" {:lesson :negative}))")
    (spit policy-file "(aguafria.zig.compiler-work/run-command! [\"probe-zig\" \"build-lib\"] (fn [] (throw (Exception. \"must not run\"))))")
    (spit fallback-file "(aguafria.zig.explain/event! {:event :disk-cache-hit}) (throw (ex-info \"intended diagnostic\" {:lesson :negative}))")
    (let [report (check/check!
                  {:report-dir (run-path) :prepared-cache-dir directory
                   :strict-load? true :parallelism 1 :compilation-modes [:prepared]
                   :scenarios [scenario
                               (-> scenario (assoc :id :wrong-stage)
                                   (assoc-in [:expect :exception :stage] :evaluate))
                               (-> scenario (assoc :id :wrong-message)
                                   (assoc-in [:expect :exception :message-pattern] "^another diagnostic$"))
                               (-> scenario (assoc :id :policy :load-files [(str policy-file)])
                                   (assoc :expect {:exception {:stage :load :type 'clojure.lang.ExceptionInfo
                                                              :message-pattern "Strict prepared consumer attempted compilation"}}))
                               (assoc scenario :id :fallback :load-files [(str fallback-file)])
                               {:id :evaluate :call 'clojure.core/read-string :args ["}"]
                                :expect {:exception {:stage :evaluate :type 'java.lang.RuntimeException
                                                     :message-pattern "Unmatched delimiter"}}}]})
          results (:results report)]
      (is (= [true false false false false true] (mapv :accepted? results)))
      (is (= [:expected-exception :failed :failed :failed :failed :expected-exception]
             (mapv :status results)))
      (is (= "intended diagnostic" (get-in results [0 :observation :exception :message])))
      (is (= :load (get-in results [0 :observation :exception :stage])))
      (is (seq (:final-compiler-attempts (nth results 3))))
      (is (seq (:final-native-events (nth results 4)))))))

(deftest compiler-configuration-is-shared-by-isolated-workers-before-loading
  (let [configuration {:module-cache-tokens {"checker-config-probe" "revision-1"}}
        report (check/check! {:report-dir (run-path) :strict-load? true
                             :compiler-configuration configuration
                             :scenarios [{:id :configured
                                          :call 'aguafria.zig.check-configuration-fixture/loaded-configuration
                                          :args [] :expect {:value configuration}}]})]
    (is (:accepted? report) (pr-str (map :failure (:results report))))
    (is (= [true true] (mapv :accepted? (:results report))))
    (is (every? #(empty? (:final-compiler-attempts %)) (:results report))))
  (doseq [configuration [nil {:cache-dir "/unexpected"} {:zig "other-zig"}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot override"
                          (check/check! {:compiler-configuration configuration
                                         :scenarios [{:id :invalid :call 'clojure.core/identity
                                                      :args [1] :expect {:value 1}}]})))))

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
        (is (empty? (filter #(= :evaluate (:stage %)) (:final-native-events r))))))
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
                                      :args [] :expect {:value :returned}}
                                     {:id :explained :call 'aguafria.zig.check-fixture/explained-fallback
                                      :args [] :expect {:value :returned}}]})
        [caught thread fallback explained] (:results r)]
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
    (is (= [{:stage :evaluate :event :disk-cache-hit}]
           (filterv #(= :evaluate (:stage %)) (:final-native-events fallback))))
    (is (= :failed (:status explained)))
    (is (= [:disk-cache-hit] (mapv :event (:forbidden-evaluation-events explained))))))

(deftest provided-cache-is-reused-and-strict-loading-is-audited
  (let [directory (Files/createTempDirectory "aguafria-check-load-" (make-array java.nio.file.attribute.FileAttribute 0))
        attempt (io/file (str directory) "attempt.clj")
        fallback (io/file (str directory) "fallback.clj")]
    (spit attempt "(try (aguafria.zig.compiler-work/run-command! [\"probe-zig\" \"build-lib\"] (fn [] (throw (Exception. \"must not run\")))) (catch clojure.lang.ExceptionInfo _ nil))")
    (spit fallback "(aguafria.zig/explain! (aguafria.zig.explain/event! {:event :disk-cache-hit :module \"load-probe\"}))")
    (let [report (check/check! {:report-dir (run-path) :prepared-cache-dir (str directory)
                                :strict-load? true :parallelism 2 :compilation-modes [:prepared]
                                :scenarios [{:id :pure :call 'clojure.core/identity :args [42] :expect {:value 42}}
                                            {:id :load-attempt :call 'clojure.core/identity :args [42]
                                             :load-files [(str attempt)] :expect {:value 42}}
                                            {:id :load-fallback :call 'clojure.core/identity :args [42]
                                             :load-files [(str fallback)] :expect {:value 42}}]})
          [pure attempted fallback] (:results report)]
      (is (= :provided-cache (get-in report [:preparation :status])))
      (is (not (.exists (io/file (:directory report) "prepare"))))
      (is (= :passed (:status pure)))
      (is (empty? (:final-compiler-attempts pure)))
      (is (= :failed (:status attempted)))
      (is (= [:load] (mapv :stage (:final-compiler-attempts attempted))))
      (is (= :failed (:status fallback)))
      (is (= [{:stage :load :event :disk-cache-hit}] (:final-native-events fallback))))))
