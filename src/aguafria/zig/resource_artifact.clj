(ns aguafria.zig.resource-artifact
  "The shared compiler recipe for resource-owned JVM images. Never loads a
  library, initializes std.testing, invokes an application, or runs a test."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files]))

(def recipe-version 2)
(def initializer-export "__aguafria_jvm_init_test_context")

(defn- command-inputs [arguments]
  (loop [remaining (seq arguments) compile [] link []]
    (if-let [argument (first remaining)]
      (cond
        ;; Preserve paired flags; in particular never mistake a --dep alias or
        ;; an include directory for an object input.
        (#{"--dep" "-I" "-isystem" "-D"} argument)
        (recur (nnext remaining) (into compile (take 2 remaining)) link)

        ;; libc also changes builtin.link_libc during semantic analysis.
        ;; It is not merely a final linker input.
        (= "-lc" argument)
        (recur (next remaining) (conj compile argument) (conj link argument))

        (and (= "-l" argument) (= "c" (second remaining)))
        (recur (nnext remaining) (into compile ["-l" "c"]) (into link ["-l" "c"]))

        (#{"-framework" "-weak_framework" "-L" "-F" "-l"} argument)
        (recur (nnext remaining) compile (into link (take 2 remaining)))

        (= "-fstrip" argument)
        (recur (next remaining) (conj compile argument) (conj link argument))

        (#{"-target" "-mcpu"} argument)
        (recur (nnext remaining) (into compile (take 2 remaining))
               (into link (take 2 remaining)))

        (or (re-matches #"-[lLF].+" argument)
            (and (.isAbsolute (io/file argument))
                 (re-find #"\.(?:a|dylib|so(?:\.[0-9]+)*|lib|o|obj)$" argument)))
        (recur (next remaining) compile (conj link argument))

        (or (str/starts-with? argument "-M")
            (re-matches #"-(?:I|D).+" argument)
            (#{"-Odebug" "-Osafe" "-Ofast" "-Osmall"
               "-ferror-tracing" "-funwind-tables" "-fPIC"} argument))
        (recur (next remaining) (conj compile argument) link)

        :else
        (throw (ex-info "Unsupported resource compiler argument"
                        {:aguafria/phase :adapter-planning
                         :reason :resource-compiler-arguments :argument argument})))
      {:compile compile :link link})))

(defn run-library-command
  "Use the real test frontend and an inert runner, then link its bitcode.
  -fno-emit-bin requests compile-only validation, not an ordinary build-lib
  validation of resource types. Exact link inputs survive the frontend split."
  [run-command command directory test-context?]
  (if-not test-context?
    (run-command command directory)
    (let [validation? (= "-fno-emit-bin" (nth command 3))
          output (if validation?
                   (str (io/file directory (str ".resource-validation-" (java.util.UUID/randomUUID))))
                   (subs (nth command 3) (count "-femit-bin=")))
          bitcode (str output ".bc")
          ;; This fixed, inert source is also retained as compiler evidence.
          ;; All invocations in an artifact directory use identical bytes.
          runner (io/file directory "jvm_resource_runner.zig")
          {:keys [compile link]} (command-inputs (drop 4 command))]
      (try
        (spit runner "pub fn main() void {}\n")
        (let [compile-command (vec (concat [(first command) "test" "--test-no-exec"
                                            "--test-runner" (.getAbsolutePath runner)
                                            "-fno-entry" "-fPIC" "-fllvm" "-fno-emit-bin"]
                                           (when-not validation? [(str "-femit-llvm-bc=" bitcode)])
                                           compile))
              compiled (assoc (run-command compile-command directory) :command compile-command)]
          (if (or validation? (not (zero? (:exit compiled))))
            compiled
            (let [link-command (vec (concat [(first command) "build-lib" bitcode "-dynamic"
                                             "-lc" (str "-femit-bin=" output)] (remove #{"-lc"} link)))]
              (assoc (run-command link-command directory) :command link-command))))
        (finally
          (Files/deleteIfExists (.toPath (io/file bitcode))))))))
