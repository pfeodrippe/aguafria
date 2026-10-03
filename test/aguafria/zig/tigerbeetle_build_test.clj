(ns aguafria.zig.tigerbeetle-build-test
  (:require [aguafria.zig.toolchain :as toolchain]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private project "examples/tigerbeetle-agua/vendor/tigerbeetle")

(defn- temporary-directory []
  (.toFile (java.nio.file.Files/createTempDirectory
            "aguafria-tiger-build-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest configure-with-explicit-test-and-ci-options
  (doseq [options [[] ["-Dtest-filter=benchmark: fixture"] ["-Dci-mode=smoke"]]]
    (let [result (apply shell/sh
                        (concat [(toolchain/executable) "build" "--help"]
                                options [:dir project]))]
      (is (zero? (:exit result)) (:err result))
      (is (str/includes? (:out result) "-Dtest-filter=[string]"))
      (is (str/includes? (:out result) "-Dci-mode=[enum]")))))

(deftest generated-source-checks-and-updates
  (let [directory (temporary-directory)
        executable (io/file directory
                            (if (= :windows (:os (toolchain/host-platform)))
                              "generated.exe" "generated"))
        compiled (shell/sh (toolchain/executable) "build-exe"
                           "src/build/generated.zig"
                           (str "-femit-bin=" executable) :dir project)
        invoke (fn [& args] (apply shell/sh (str executable) (map str args)))
        source (io/file directory "source.txt")
        target (io/file directory "target.txt")]
    (is (zero? (:exit compiled)) (:err compiled))
    (when (zero? (:exit compiled))
      (spit source "new source\n")
      (spit target "old source\n")
      (testing "CI checks report stale files without changing them"
        (is (not (zero? (:exit (invoke "file" "check" source target)))))
        (is (= "old source\n" (slurp target))))
      (testing "local updates and subsequent freshness checks"
        (is (zero? (:exit (invoke "file" "update" source target))))
        (is (= "new source\n" (slurp target)))
        (is (zero? (:exit (invoke "file" "check" source target)))))
      (testing "missing targets are stale and are not created by checks"
        (let [missing (io/file directory "missing.txt")]
          (is (not (zero? (:exit (invoke "file" "check" source missing)))))
          (is (not (.exists missing)))))
      (testing "directory updates preserve unrelated source files"
        (let [input (io/file directory "input")
              output (io/file directory "output")
              missing (io/file directory "missing-directory")]
          (.mkdir input)
          (.mkdir output)
          (spit (io/file input "generated.txt") "generated\n")
          (spit (io/file output "handwritten.txt") "handwritten\n")
          (is (not (zero? (:exit (invoke "directory" "check" input missing)))))
          (is (not (.exists missing)))
          (is (not (zero? (:exit (invoke "directory" "check" input output)))))
          (is (zero? (:exit (invoke "directory" "update" input output))))
          (is (= "generated\n" (slurp (io/file output "generated.txt"))))
          (is (= "handwritten\n" (slurp (io/file output "handwritten.txt"))))
          (is (zero? (:exit (invoke "directory" "check" input output))))))
      (testing "executable-path reporting"
        (let [result (invoke "print" source)]
          (is (zero? (:exit result)))
          (is (= (str source "\n") (:out result)))))
      (is (not (zero? (:exit (invoke "file" "unknown-mode" source target))))))))
