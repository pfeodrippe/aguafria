(ns aguafria.zig.namespace-admission-test
  (:require [aguafria.zig.bundle :as bundle]
            [aguafria.zig.precompile :as precompile]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest rejecting-an-owner-preserves-shared-and-explicit-artifacts
  (let [collected (atom {})
        shared {:module "fixture" :hash "shared" :validation-pending? true}
        unique (assoc shared :hash "unique")
        explicit (assoc shared :hash "explicit")]
    (binding [bundle/*preparing* collected]
      (binding [bundle/*preparation-owner* 'rejected]
        (bundle/observe! shared)
        (bundle/observe! unique))
      (binding [bundle/*preparation-owner* 'accepted]
        (bundle/observe! shared))
      (bundle/observe! explicit))
    (swap! collected assoc :excluded {"bad" {:owner 'rejected}
                                      "good" {:owner 'accepted}})
    (bundle/exclude-preparation-owners! collected #{'rejected})
    (is (= {"good" {:owner 'accepted}} (:excluded @collected)))
    (is (= #{"shared" "explicit"} (set (map :hash (vals (:artifacts @collected))))))
    (is (= #{'rejected 'accepted}
           (get-in @collected [:artifact-owners (#'bundle/artifact-id shared)])))
    (swap! collected assoc-in [:validation-results (#'bundle/artifact-id shared)] {:status :failed})
    (is (= #{'rejected 'accepted} (bundle/rejected-preparation-owners collected)))
    (swap! collected assoc-in [:validation-results (#'bundle/artifact-id explicit)] {:status :failed})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"explicit native preparation input"
                          (bundle/rejected-preparation-owners collected)))))

(deftest admission-infrastructure-failure-cannot-become-a-source-rejection
  (let [collected (atom {:artifacts {"entry" {:module "fixture" :hash "entry"
                                              :validation-pending? true}}})
        before @collected]
    (with-redefs-fn {#'bundle/candidate identity
                     #'bundle/validate-batch!
                     (fn [& _] (throw (ex-info "Compiler terminated" {:exit 137})))}
      #(is (= 137 (try (bundle/validate-pending! "unused" collected {})
                       nil
                       (catch clojure.lang.ExceptionInfo error (:exit (ex-data error)))))))
    (is (= before @collected))))

(deftest source-and-normal-context-rejections-do-not-poison-working-namespaces
  (let [configuration (runtime/configuration)
        cache (str (Files/createTempDirectory "aguafria-namespace-admission-"
                                              (make-array java.nio.file.attribute.FileAttribute 0)))
        good 'aguafria.zig.namespace-admission-valid-fixture
        bad 'aguafria.zig.namespace-admission-invalid-fixture
        contextual 'aguafria.zig.namespace-admission-context-fixture
        original-run @#'runtime/run-command]
    (try
      (runtime/configure! {:cache-dir cache})
      (let [report (precompile/precompile! {:analyze [good bad contextual]
                                            :report-file (str cache "/report.edn")})
            rejections (into {} (map (juxt :namespace :reason))
                             (get-in report [:namespace-admission :rejected-namespaces]))
            images (into {} (map (juxt :namespace identity)) (:namespace-images report))]
        (is (= :source-discovery (get rejections bad)))
        (is (= :ordinary-jvm-compilation (get rejections contextual)))
        (is (not (contains? rejections good)))
        (is (= :rejected (:status (get images bad))))
        (is (= :rejected (:status (get images contextual))))
        (is (= :prepared (:status (get images good))))
        (is (pos? (get-in report [:bundles :packed-handlers])))
        (is (= 1 (get-in report [:bundles :compiler-invocations])))
        (is (pos? (get-in report [:compiler-work :by-phase :namespace-admission :compiler "build-lib"])))
        (is (= {:analyzed 1 :rejected 2} (get-in report [:coverage :namespaces :statuses])))
        (is (= #{bad contextual}
               (set (map :namespace (get-in report [:rejected-preparation :analysis])))))
        (is (empty? (:functions (first (filter #(= bad (:namespace %))
                                               (get-in report [:rejected-preparation :analysis]))))))
        (doseq [image (get-in report [:rejected-preparation :namespace-images])]
          (is (nil? (bundle/find-artifact cache
                                          (assoc (:artifact image) :module (str (:namespace image)))))))
        (let [manifests (filter #(= "manifest.edn" (.getName %))
                                (file-seq (io/file cache "bundles")))]
          (is (= 1 (count manifests))))
        (with-redefs-fn
          {#'runtime/run-command
           (fn [command directory]
             (when (#{"build-lib" "test" "test-obj"} (second command))
               (throw (ex-info "Prepared valid namespace compiled again" {:command command})))
             (original-run command directory))}
          #(with-open [result ((requiring-resolve
                                'aguafria.zig.namespace-admission-valid-fixture/increment) 41)]
             (is (= 42 (long @result))))))
      (finally (runtime/configure! configuration)))))
