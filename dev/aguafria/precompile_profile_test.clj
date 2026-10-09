(ns aguafria.precompile-profile-test
  (:require [aguafria.precompile-profile :as profile]
            [aguafria.profile-analysis :as analysis]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]
           [java.util.concurrent ConcurrentLinkedQueue]))

(defn- state []
  (#'profile/recorder
   (str (Files/createTempDirectory "aguafria-profile-test-"
                                   (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest child-resource-output-is-parsed-with-units
  (is (= {:real-seconds 1.23 :user-seconds 0.8 :sys-seconds 0.03
          :max-rss-bytes 32768.0}
         (profile/parse-time
          "real 1.23\nuser 0.80\nsys 0.03\n 32768  maximum resident set size\n")))
  (is (every? nil? (vals (profile/parse-time "")))))

(deftest spans-return-the-result-and-propagate-errors
  (let [state (state)]
    (is (= :same (#'profile/span state :outer identity [:same])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expected"
                          (#'profile/span state :negative
                                          #(throw (ex-info "expected" {})) [])))
    (is (= [:ok :threw] (mapv :status (:events state))))
    (is (empty? profile/*context*))))

(deftest shell-wrapper-keeps-options-and-compiler-diagnostics
  (let [state (state)
        original shell/sh
        wrapper (fn [& args] (#'profile/measured-sh state original args))]
    (with-redefs [shell/sh wrapper]
      (is (= {:exit 9 :out "input" :err "failure"}
             (shell/sh "/bin/sh" "-c" "printf failure >&2; cat; exit 9" :in "input")))
      (is (= {:exit 0 :out (str (.getCanonicalPath (io/file (:directory state))) "\n")
              :err ""}
             (shell/sh "/bin/pwd" :dir (:directory state)))))
    (is (= [9 0] (mapv :exit (:events state))))
    (is (every? number? (map :user-seconds (:events state))))))

(deftest overlapping-intervals-are-not-double-counted
  (doseq [[intervals expected] [[[] 0.0]
                                [[[0 4] [2 6] [8 9]] 7.0]
                                [[[2 3] [0 9]] 9.0]
                                [[[1 2] [2 4]] 3.0]]]
    (is (= expected (analysis/interval-union-ms intervals)))))

(deftest recording-is-bounded-and-reports-overflow
  (let [state (state)]
    (with-redefs [profile/max-events 2]
      (dotimes [index 5] (#'profile/record! state {:index index})))
    (is (= 2 (.size ^ConcurrentLinkedQueue (:events state))))
    (is (= 3 (.get ^java.util.concurrent.atomic.AtomicLong (:dropped state))))))

(deftest sample-is-fixed-and-stages-resolve
  (is (= 10 (count profile/sample-namespaces)))
  (is (= 10 (count (distinct profile/sample-namespaces))))
  (is (= (inc (count profile/stages)) (count (#'profile/wrappers (state))))))
