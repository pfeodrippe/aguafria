(ns aguafria.zig.shared-support-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest shared-writer-uses-status-not-foreign-zig-errors
  (let [directory (Files/createTempDirectory "aguafria-writer-abi-"
                                             (make-array java.nio.file.attribute.FileAttribute 0))
        source (io/file (.toFile directory) "writer_test.zig")]
    (spit source (str (slurp (io/resource "aguafria/jvm_result.zig"))
                      "\n" (slurp (io/resource "aguafria/zig/shared_writer_fixture.zig"))))
    (let [result (shell/sh (runtime/zig-executable) "test" (.getAbsolutePath source))]
      (is (zero? (:exit result)) (:err result))
      (is (str/includes? (:err result) "All 3 tests passed.")))))

(deftest adapter-optimization-keeps-safety
  (let [previous (runtime/configuration)]
    (try
      (is (= "ReleaseSafe" (:jvm-optimize (runtime/configure! {:jvm-optimize "ReleaseSafe"}))))
      (is (= "Debug" (:jvm-optimize (runtime/configure! {:jvm-optimize "Debug"}))))
      (doseq [mode ["ReleaseFast" "ReleaseSmall" "invalid" nil]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"safety-checked"
                              (runtime/configure! {:jvm-optimize mode}))))
      (finally (runtime/configure! previous)))))

(deftest shared-storage-survives-adapter-boundaries
  (with-open [array (k/var (az/array [1 2 3 4] :i32))]
    (let [slice (az/slice array 1 3)]
      (k/+= (az/get slice 0) 40)
      (is (= 42 (az/value (az/get array 1))))
      (is (= 2 (az/value (:len slice))))))
  ;; Writers and native values are allocated/released through shared support,
  ;; including results created by several distinct specialized libraries.
  (dotimes [index 10]
    (with-open [value (k/i32 index)
                increment (k/i32 2)
                result (k/+ value increment)]
      (is (= (+ index 2) (az/value result)))))
  (when (str/includes? (System/getProperty "os.name") "Mac")
    (let [root (io/file (:cache-dir (runtime/configuration)))
          support (filter #(and (.isFile %) (str/ends-with? (.getName %) ".dylib"))
                          (file-seq (io/file root "development-support")))]
      (is (seq support))
      (is (some #(str/includes? (:out (shell/sh "nm" "-g" (.getAbsolutePath %)))
                                "_aguafria_jvm_writer_new") support)))))
