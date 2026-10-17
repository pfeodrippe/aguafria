(ns aguafria.zig.prepared-rejection-native-test
  (:require [aguafria.zig.check :as check]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest ordinary-load-rejection-is-verified-before-publication-and-reused-without-compiler-work
  (let [root (str (Files/createTempDirectory "aguafria-rejection-native-"
                                           (make-array java.nio.file.attribute.FileAttribute 0)))
        namespace 'aguafria.zig.prepared-rejection-fixture
        source (.getCanonicalPath (io/file "test/aguafria/zig/prepared_rejection_fixture.clj"))
        report (check/check!
                {:report-dir (str (io/file root "check"))
                 :compilation-modes [:prepared] :strict-load? true
                 :parallelism 1 :heap-mib 768 :timeout-seconds 240
                 :precompile-options {:analyze [namespace] :parallelism 16
                                      :source-files {namespace source}}
                 :scenarios [{:id :rejected-load :call 'clojure.core/identity :args [:never]
                              :load-files [source]
                              :expect {:exception {:stage :load :type 'clojure.lang.ExceptionInfo
                                                   :message-pattern "cannot assign to constant"
                                                   :data {:aguafria/phase :zig-compile
                                                          :aguafria/prepared-rejection? true
                                                          :module (str namespace) :exit 1}}}}]})
        result (first (:results report))
        preparation (get-in report [:preparation :preparation-report])
        proof (first (:prepared-rejections preparation))]
    (is (:accepted? report) (str (:directory report)))
    (is (= :expected-exception (:status result)))
    (is (empty? (:final-compiler-attempts result)))
    (is (not-any? #(#{:compiled :compile-failed :disk-cache-hit} (:event %))
                  (:final-all-native-events result)))
    (is (= 1 (count (filter #(= :prepared-rejection-hit (:event %))
                           (:final-all-native-events result)))))
    (is (string? (get-in result [:observation :exception :data :recipe-id])))
    (is (string? (get-in result [:observation :exception :data :rejection-id])))
    (is (= source (get-in result [:observation :exception :data :clojure.error/source])))
    (is (seq (get-in result [:observation :exception :data :diagnostics])))
    (is (= :verified (:status proof)))
    (is (= 1 (get-in proof [:compiler-result :exit])))
    (is (= "build-lib" (get-in proof [:compiler-result :command 1])))
    (is (.startsWith ^String (get-in proof [:compiler-result :command 3]) "-femit-bin="))
    (is (= (:rejection-id proof) (get-in result [:observation :exception :data :rejection-id])))
    (is (= :rejected (:status (first (filter #(= namespace (:namespace %)) (:analysis preparation))))))
    (let [copy (str (io/file root "relocated.clj"))
          cache (str (io/file root "check/prepared-cache"))
          expected {:exception {:stage :load :type 'clojure.lang.ExceptionInfo
                                :message-pattern "cannot assign to constant"
                                :data {:aguafria/prepared-rejection? true}}}
          _ (spit copy (slurp source))
          miss (check/check!
                {:report-dir (str (io/file root "path-miss"))
                 :prepared-cache-dir cache :compilation-modes [:prepared] :strict-load? true
                 :heap-mib 768 :timeout-seconds 120
                 :scenarios [{:id :changed-source-identity :call 'clojure.core/identity :args [:never]
                              :load-files [copy] :expect expected}]})
          configuration-miss (check/check!
                              {:report-dir (str (io/file root "configuration-miss"))
                               :prepared-cache-dir cache :compilation-modes [:prepared] :strict-load? true
                               :compiler-configuration {:optimize "safe"}
                               :heap-mib 768 :timeout-seconds 120
                               :scenarios [{:id :changed-configuration :call 'clojure.core/identity :args [:never]
                                            :load-files [source] :expect expected}]})]
      (doseq [r [miss configuration-miss]]
        (is (false? (:accepted? r)))
        (is (seq (:final-compiler-attempts (first (:results r)))))
        (is (not-any? #(= :prepared-rejection-hit (:event %))
                      (:final-all-native-events (first (:results r)))))))))
