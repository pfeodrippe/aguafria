(ns aguafria.zig.native-test-object-test
  (:require [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest test-object-link-inputs-are-not-lost-after-the-frontend
  (let [options {:zig "/zig" :optimize "debug" :zig-args ["-lm" "-framework" "Foundation"]
                 :modules {"provider" "/source/provider.zig"}
                 :module-zig-args {"provider" ["-L/vendor" "-lprovider"]}}
        files (map io/file ["/source/test.zig" "/source/runner.zig" "/cache/test.o" "/cache/test.dylib"])
        command #(apply #'runtime/native-test-commands % "test.test.owner"
                        (concat files [{:path "/cache/panic.dylib"}]))
        result (command options)]
    (is (= ["-lc" "-lm" "-framework" "Foundation" "-L/vendor" "-lprovider"] (:link-arguments result)))
    (is (= (:link-arguments result) (subvec (:link-command result) 5 11)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Compiler configuration"
                          (command (assoc options :zig-args ["--unsupported-link-mode"]))))))

(deftest test-object-commands-preserve-discovery-codegen-and-link-context
  (doseq [[target cpu] [[nil nil] ["aarch64-macos" "apple_m1"] ["x86_64-linux-gnu" "baseline"]]]
    (let [options {:zig "/zig" :optimize "debug" :target target :cpu cpu
                   :modules {"provider" "/source/provider.zig"}
                   :module-dependencies {:root ["provider"]}
                   :zig-args ["-ferror-tracing"]}
          files (map io/file ["/source/test.zig" "/source/runner.zig"
                              "/cache/test.o" "/cache/test.dylib"])
          {:keys [compile-command link-command]}
          (apply #'runtime/native-test-commands
                 options "test.test.owner" (concat files [{:path "/cache/panic.dylib"}]))]
      (is (= ["/zig" "test-obj" "--test-filter" "test.test.owner"]
             (subvec compile-command 0 4)))
      (is (every? (set compile-command) ["--test-no-exec" "--test-runner"
                                         "/source/runner.zig" "-fPIC" "-fllvm" "-Odebug"]))
      (is (some #{"-femit-bin=/cache/test.o"} compile-command))
      (is (some #{"-Mroot=/source/test.zig"} compile-command))
      (is (some #{"-Mprovider=/source/provider.zig"} compile-command))
      (is (some #{"-ferror-tracing"} compile-command))
      (is (not-any? #(str/starts-with? % "-femit-llvm-bc") compile-command))
      (is (= ["/zig" "build-lib" "/cache/test.o" "/cache/panic.dylib"]
             (subvec link-command 0 4)))
      (is (every? (set link-command) ["-dynamic" "-lc" "-femit-bin=/cache/test.dylib"]))
      (when target
        (is (= ["-target" target "-mcpu" cpu] (subvec link-command 7)))))))
