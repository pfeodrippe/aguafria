(ns aguafria.spirv
  "Aguafria shader compilation and SPIR-V instructions."
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(def ^:private glsl-opcodes
  {"Pow" 26
   "FClamp" 43
   "FMix" 46
   "SmoothStep" 49
   "UnpackUnorm4x8" 64
   "Length" 66
   "Cross" 68
   "Normalize" 69})

(defmacro glsl
  "Emit one SPIR-V math instruction from the GLSL.std.450 instruction set.
  This generates Zig inline SPIR-V assembly, not GLSL shader source.
  Use ordinary k/ builtins where available; this helper exposes operations
  such as Normalize, FMix and UnpackUnorm4x8 with an explicit result type."
  [result-type instruction & arguments]
  (let [opcode (or (get glsl-opcodes instruction)
                   (when (integer? instruction) instruction)
                   (throw (ex-info "Unknown GLSL.std.450 instruction" {:instruction instruction})))
        names (mapv #(symbol (str "arg" %)) (range (count arguments)))]
    `(k/asm ~(str "%glsl = OpExtInstImport \"GLSL.std.450\"\n"
                  "%result = OpExtInst %Result %glsl " opcode
                  " " (str/join " " (map #(str "%" %) names)))
            {:outputs [[~'result "" {:type ~result-type}]]
             :inputs [[~'Result "t" (a/type ~result-type)]
                      ~@(mapv (fn [n arg] [n "" arg]) names arguments)]})))

(defmacro instruction
  "Emit a SPIR-V instruction with an explicit Zig result type."
  [result-type opcode & arguments]
  (let [names (mapv #(symbol (str "arg" %)) (range (count arguments)))]
    `(k/asm ~(str "%result = " opcode " %Result " (str/join " " (map #(str "%" %) names)))
            {:outputs [[~'result "" {:type ~result-type}]]
             :inputs [[~'Result "t" (a/type ~result-type)]
                      ~@(mapv (fn [n arg] [n "" arg]) names arguments)]})))

(defmacro discard
  "Terminate the current fragment invocation."
  []
  `(k/asm "OpKill" {:attrs #{k/volatile}}))

(defn- run-tool!
  [command]
  (let [process (.start (doto (ProcessBuilder. ^java.util.List command)
                          (.redirectErrorStream true)))
        output (slurp (.getInputStream process))
        exit (.waitFor process)]
    (when-not (zero? exit)
      (throw (ex-info "SPIR-V processing failed" {:command command :exit exit :output output})))
    output))

(defn diagnostics
  "Keep the compiler message and tool output when an exception is wrapped."
  [error]
  (->> (iterate ex-cause error)
       (take-while some?)
       (mapcat (fn [cause] [(ex-message cause) (:output (ex-data cause))]))
       (remove str/blank?)
       distinct
       (str/join "\n\n")))

(defn compile!
  "Compile a shader namespace, optimize and validate Vulkan SPIR-V, then
  atomically publish the binary. Failure leaves the last binary untouched.
  The renderer must separately create and publish its replacement pipeline."
  ([namespace output] (compile! namespace output {}))
  ([namespace output {:keys [target] :or {target "spirv32-vulkan"}}]
   (when-not (#{"spirv32-vulkan" "spirv64-vulkan"} target)
     (throw (ex-info "Shader target must be SPIR-V Vulkan" {:target target})))
   (let [shader-target target]
     (locking compile!
       (binding [runtime/*source-only-registration?* true]
         (require namespace :reload))
       (let [target (.toPath (.getAbsoluteFile (io/file output)))
             directory (.getParent target)
             _ (Files/createDirectories directory (make-array FileAttribute 0))
             raw (Files/createTempFile directory ".shader-" ".raw.spv" (make-array FileAttribute 0))
             candidate (Files/createTempFile directory ".shader-" ".spv"
                                              (make-array FileAttribute 0))]
         (try (let [artifact (a/build! namespace
                                        {:kind :exe
                                         :target shader-target
                                         :cpu "vulkan_v1_2"
                                         :optimize "fast"
                                         :zig-args ["-fno-llvm"]
                                         :output (str raw)})]
                ;; Zig emits unused interface type copies. Remove them before
                ;; Vulkan validation; only the validated result can become a
                ;; GPU candidate.
                (run-tool! ["spirv-opt" "--skip-validation" "-O" (str raw) "-o" (str candidate)])
                (run-tool! ["spirv-val" "--target-env" "vulkan1.2" (str candidate)])
                (Files/move candidate
                            target
                            (into-array StandardCopyOption
                                        [StandardCopyOption/ATOMIC_MOVE
                                         StandardCopyOption/REPLACE_EXISTING]))
                (assoc artifact
                  :output-path (str target)
                  :validated? true
                  :bytes (Files/size target)))
              (finally (Files/deleteIfExists raw) (Files/deleteIfExists candidate))))))))
