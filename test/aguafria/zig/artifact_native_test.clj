(ns aguafria.zig.artifact-native-test
  (:require [aguafria.zig.emitter :as emitter]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker MemoryLayout SymbolLookup ValueLayout]
           [java.nio.file Files]
           [java.nio.file.attribute FileTime]
           [java.util ArrayList]))

(defn- probe [artifact]
  (with-open [arena (Arena/ofConfined)]
    (let [lookup (SymbolLookup/libraryLookup (.toPath (io/file (:library-path artifact))) arena)
          handle (.downcallHandle (Linker/nativeLinker)
                                  (.get (.find lookup "artifact_probe"))
                                  (FunctionDescriptor/of ValueLayout/JAVA_INT (make-array MemoryLayout 0))
                                  (make-array java.lang.foreign.Linker$Option 0))]
      (.invokeWithArguments handle (ArrayList.)))))

(deftest adapter-tracking-counts-nested-calls-and-unwinds-to-zero
  (let [module "aguafria.adapter-tracking-native-fixture"
        declaration {:kind :fn :name 'count-depth :public? true
                     :jvm-adapter? true :return :usize
                     :declaration-key [:fn 'count-depth]
                     :args [{:name 'n :type :usize}] :body []
                     :body-prefix-source
                     (str "if (n == 0) return @atomicLoad(usize, &__active_calls, .acquire);\n"
                          "return count_depth(n - 1);")}
        specs {[:fn 'count-depth]
               {:implementation "__impl" :dispatch-type "__fn_type"
                :dispatch "__dispatch" :getter "__implementation_address"
                :setter "__set_dispatch" :active-counter "__active_calls"
                :active-depth "__active_depth" :active-tracking "__track_active_calls"
                :active-tracking-setter "__set_active_tracking"
                :active-getter "__active_call_count"
                :publication-epoch "__publication_epoch"
                :publication-epoch-setter "__set_publication_epoch"}}
        source (emitter/emit-reloadable-module
                module [declaration] specs {}
                {:top-level? true
                 :extra-body-source
                 (str "export fn tracking_probe(n: usize) callconv(.c) usize {\n"
                      "    return count_depth(n);\n}")})
        directory (.toFile (Files/createTempDirectory
                            "aguafria-adapter-tracking-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        source-file (io/file directory "tracking.zig")
        library (io/file directory (System/mapLibraryName "tracking"))
        _ (spit source-file source)
        compilation (shell/sh (runtime/zig-executable) "build-lib" "-dynamic" "-Osafe"
                              (str "-femit-bin=" library) (str source-file))
        _ (assert (zero? (:exit compilation)) (pr-str compilation))
        linker (Linker/nativeLinker)]
    (with-open [arena (Arena/ofConfined)]
      (let [lookup (SymbolLookup/libraryLookup (.toPath library) arena)
            count-handle (.downcallHandle
                          linker (.orElseThrow (.find lookup "__active_call_count"))
                          (FunctionDescriptor/of ValueLayout/JAVA_LONG (make-array MemoryLayout 0))
                          (make-array java.lang.foreign.Linker$Option 0))
            call-handle (.downcallHandle
                         linker (.orElseThrow (.find lookup "tracking_probe"))
                         (FunctionDescriptor/of ValueLayout/JAVA_LONG
                                                (into-array MemoryLayout [ValueLayout/JAVA_LONG]))
                         (make-array java.lang.foreign.Linker$Option 0))]
        (is (zero? (.invokeWithArguments count-handle (ArrayList.))))
        (doseq [depth [0 1 7 32]]
          (is (= (inc depth) (.invokeWithArguments call-handle (ArrayList. [(long depth)]))))
          (is (zero? (.invokeWithArguments count-handle (ArrayList.)))
              "All nested adapter calls must release their active-call references"))))))

(deftest jvm-only-adapters-load-beyond-the-macos-tls-image-ceiling
  (when (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
    (let [source (io/file (io/resource "fixtures/jvm/AdapterLibraryStress.clj"))
          process (.start
                   (doto (ProcessBuilder.
                          ^java.util.List
                          [(str (System/getProperty "java.home") "/bin/java")
                           "--enable-native-access=ALL-UNNAMED"
                           "-cp" (System/getProperty "java.class.path")
                           "clojure.main" (.getAbsolutePath source)])
                     (.redirectErrorStream true)))
          output (future (slurp (.getInputStream process)))
          finished? (.waitFor process 120 java.util.concurrent.TimeUnit/SECONDS)]
      (when-not finished? (.destroyForcibly process))
      (is finished? "Native image stress test must terminate")
      (is (and finished? (zero? (.exitValue process))) @output)
      (is (str/includes? @output ":loaded-images 640") @output)
      (is (str/includes? @output ":unique-symbol-addresses 640") @output)
      (is (str/includes? @output ":tls-sections 0") @output))))

(deftest relocated-object-reuses-real-library-and-changed-object-invalidates
  (let [directory (.toFile (Files/createTempDirectory "aguafria-artifact-native-"
                                                      (make-array java.nio.file.attribute.FileAttribute 0)))
        previous (runtime/configuration)
        source (io/file directory "dependency.zig")
        a (io/file directory "project-a.o")
        b (io/file directory "project-b.o")
        build-object! (fn [n]
                        (spit source (str "export fn artifact_external() i32 { return " n "; }"))
                        (let [result (shell/sh (runtime/zig-executable) "build-obj" "-fPIC"
                                               "-Osafe" (str "-femit-bin=" a) (str source))]
                          (assert (zero? (:exit result)) (pr-str result))))
        compile! (fn [object]
                   (runtime/configure! {:cache-dir (str (io/file directory "cache"))
                                        :zig-args [(str object)]})
                   (#'runtime/compile-source!
                    "aguafria.artifact-key-fixture"
                    (str "extern fn artifact_external() i32;\n"
                         "export fn artifact_probe() i32 { return artifact_external(); }\n")
                    [{:module "aguafria.artifact-key-fixture" :kind :fn
                      :name 'artifact_probe
                      :qualified-name 'aguafria.artifact-key-fixture/artifact_probe
                      :args [] :return :i32}]))]
    (try
      (build-object! 7)
      (Files/copy (.toPath a) (.toPath b) (make-array java.nio.file.CopyOption 0))
      (let [first-build (compile! a)
            _ (Files/setLastModifiedTime (.toPath a) (FileTime/fromMillis 1000))
            touched (compile! a)
            relocated (compile! b)]
        (is (false? (:cached? first-build)))
        (is (= 7 (probe first-build)))
        (is (:cached? touched))
        (is (:cached? relocated))
        (is (= (:hash first-build) (:hash touched) (:hash relocated)))
        (is (= (:library-path first-build) (:library-path relocated)))
        (build-object! 9)
        (let [changed (compile! a)]
          (is (false? (:cached? changed)))
          (is (not= (:hash first-build) (:hash changed)))
          (is (= 9 (probe changed)))))
      (finally (runtime/configure! previous)))))
