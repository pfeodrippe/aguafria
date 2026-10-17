(ns aguafria.zig.operation-probe-field-test
  (:require [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest top-level-field-type-reflection-does-not-read-undefined-pointers
  (let [directory (str (Files/createTempDirectory "aguafria-pointer-field-reflection-"
                                                 (make-array java.nio.file.attribute.FileAttribute 0)))
        result (shell/sh (runtime/zig-executable) "test" "--test-no-exec" "-fno-emit-bin"
                         "--cache-dir" (str (io/file directory "local"))
                         "--global-cache-dir" (str (io/file directory "global"))
                         "--dep" "operation_probe"
                         (str "-Mroot=" (.getAbsolutePath
                                         (io/file "test/fixtures/inspection_pointer_fields/main.zig")))
                         (str "-Moperation_probe=" (.getAbsolutePath
                                                    (io/file "resources/aguafria/operation_probe.zig"))))]
    (is (zero? (:exit result)) (:err result))))
