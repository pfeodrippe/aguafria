(ns learn.wasm-examples-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.io File]
           [java.nio.file Files]))

(deftest wasm-comments-run-on-their-real-targets
  (let [directory (.toFile (Files/createTempDirectory "aguafria-wasm-examples-"
                                                      (make-array java.nio.file.attribute.FileAttribute 0)))
        classpath (->> (str/split (System/getProperty "java.class.path")
                                  (re-pattern (java.util.regex.Pattern/quote File/pathSeparator)))
                       (map #(.getAbsolutePath (io/file %)))
                       (str/join File/pathSeparator))
        result (sh/sh (str (System/getProperty "java.home") "/bin/java")
                      "-Xmx1024m" "-XX:ActiveProcessorCount=2"
                      "--enable-native-access=ALL-UNNAMED"
                      "-cp" classpath "clojure.main" "-m" "learn.wasm-examples-check"
                      :dir directory)]
    ;; Retain diagnostics/artifacts if the regression fails.
    (spit (io/file directory "stdout.log") (:out result))
    (spit (io/file directory "stderr.log") (:err result))
    (is (zero? (:exit result)) (str "Evidence: " directory "\n" (:out result) (:err result)))
    (is (str/includes? (:out result) ":wasm-examples :all-passed"))))
