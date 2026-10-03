(ns aguafria.spirv-test
  (:require [aguafria.spirv :as spv]
            [aguafria.spirv-fixture-options :as options]
            [aguafria.zig.emitter :as emit]
            [aguafria.zig.runtime :as runtime]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest wrapped-shader-errors-keep-the-compiler-output
  (let [error (ex-info "Shader compilation failed" {}
                       (ex-info "Invalid shader" {:output "shader.clj:12:5: type mismatch"}))
        text (spv/diagnostics error)]
    (is (str/includes? text "Shader compilation failed"))
    (is (str/includes? text "Invalid shader"))
    (is (str/includes? text "shader.clj:12:5: type mismatch"))))

(deftest shader-calling-conventions-are-explicit
  (doseq [[convention expected] [[:.spirv_vertex "callconv(.spirv_vertex)"]
                                 [{:spirv_fragment {}} "callconv(.{.spirv_fragment = .{}})"]]]
    (let [declaration {:kind :fn
                       :name 'entry
                       :return :void
                       :body []
                       :args []
                       :export? true
                       :callconv convention}
          source (emit/emit-declaration declaration)]
      (is (str/includes? source expected))
      (is (not (str/includes? source "callconv(.c)")))
      (is (= convention (:calling-convention (#'runtime/callable-abi declaration)))))))

(deftest zig-builtins-and-explicit-math-use-spirv-extended-instructions
  (let [directory (Files/createTempDirectory "aguafria-spirv-math-"
                                             (make-array FileAttribute 0))
        output (.resolve directory "math.spv")]
    (try
      (let [built (spv/compile! 'aguafria.spirv-math-fixture (str output))
            assembly (#'spv/run-tool! ["spirv-dis" (str output)])]
        (is (:validated? built))
        (is (str/includes? assembly "OpExtInstImport \"GLSL.std.450\""))
        (is (re-find #"OpExtInst .* Exp " assembly))
        (is (re-find #"OpExtInst .* Normalize " assembly)))
      (finally
        (Files/deleteIfExists output)
        (Files/delete directory)))))

(deftest compile-validate-and-publish-shaders-transactionally
  (let [directory (Files/createTempDirectory "aguafria-spirv-test-" (make-array FileAttribute 0))
        output (.resolve directory "shader.spv")
        bytes #(vec (Files/readAllBytes output))
        compile! #(spv/compile! 'aguafria.spirv-fixture (str output))]
    (try (let [built (compile!)
               original (bytes)]
           (is (:validated? built))
           (is (pos? (:bytes built)))
           (is (= [3 2 35 7] (mapv #(bit-and 255 %) (take 4 original))))
           (is (str/includes? (slurp (:source-path built)) "@SpirvType"))
           (testing "an actual Zig compilation failure retains the last good binary"
             (binding [options/*intensity* "invalid float"]
               (is (thrown? Throwable (compile!))))
             (is (= original (bytes))))
           (testing "a validation failure cannot publish the candidate"
             (with-redefs-fn {#'spv/run-tool! (fn [_] (throw (ex-info "Invalid SPIR-V" {})))}
               #(is (thrown? clojure.lang.ExceptionInfo (compile!))))
             (is (= original (bytes))))
           (testing "a valid edit changes the binary and restoring it is reproducible"
             (binding [options/*intensity* 0.25]
               (is (:validated? (compile!))))
             (is (not= original (bytes)))
             (is (:validated? (compile!)))
             (is (= original (bytes)))))
         (finally (Files/deleteIfExists output) (Files/delete directory)))))
