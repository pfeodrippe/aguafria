(ns aguafria.compiler-jobs-benchmark-test
  (:require [aguafria.compiler-jobs-benchmark :as benchmark]
            [clojure.test :refer [deftest is]]))

(def events
  [{:kind :command
    :command ["zig" "test-obj" "--test-filter" "example.test.original"
              "-femit-bin=old.o" "-Mroot=original.zig"]}
   {:kind :command
    :command ["zig" "build-lib" "old.o" "support.dylib"
              "-dynamic" "-femit-bin=old.dylib"]}])

(deftest replays-preserve-sources-selectors-and-link-inputs
  (let [plan (first (benchmark/plans events "/tmp/compiler-job-planning-only" 2 1))]
    (is (= ["zig" "test-obj" "-j2" "--test-filter" "example.test.original"
            "-femit-bin=/tmp/compiler-job-planning-only/0/test.o" "-Mroot=original.zig"]
           (:compile plan)))
    (is (= ["zig" "build-lib" "-j2" "/tmp/compiler-job-planning-only/0/test.o"
            "support.dylib" "-dynamic"
            "-femit-bin=/tmp/compiler-job-planning-only/0/libtest.dylib"]
           (:link plan)))))

(deftest default-job-count-does-not-change-compiler-flags
  (let [plan (first (benchmark/plans events "/tmp/compiler-job-planning-only" nil 1))]
    (is (= ["zig" "test-obj" "--test-filter" "example.test.original"]
           (subvec (:compile plan) 0 4)))
    (is (= ["zig" "build-lib" "/tmp/compiler-job-planning-only/0/test.o"]
           (subvec (:link plan) 0 3)))))

(deftest invalid-or-incomplete-replays-are-rejected
  (doseq [[jobs count] [[0 1] [17 1] [1 0] [1 21]]]
    (is (thrown? clojure.lang.ExceptionInfo (benchmark/plans events "/tmp/unused" jobs count))))
  (is (thrown? clojure.lang.ExceptionInfo (benchmark/plans events "/tmp/unused" 1 2)))
  (is (thrown? clojure.lang.ExceptionInfo
               (benchmark/plans (take 1 events) "/tmp/unused" 1 1))))
