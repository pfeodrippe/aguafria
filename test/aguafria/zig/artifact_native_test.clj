(ns aguafria.zig.artifact-native-test
  (:require [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
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
                                               "-OReleaseSafe" (str "-femit-bin=" a) (str source))]
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
