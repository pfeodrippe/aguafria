(ns aguafria.zig.dispatch-profile-test
  (:require [aguafria.zig.artifact :as artifact]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker MemoryLayout SymbolLookup ValueLayout]
           [java.nio.file Files]
           [java.util ArrayList]))

(defn- development-options [options]
  ((or (ns-resolve 'aguafria.zig.runtime 'development-dispatch-abi-options)
       (throw (ex-info "Development dispatch ABI normalizer is missing" {})))
   options))

(deftest native-and-jvm-development-profiles-share-error-tracing-not-optimization
  (doseq [mode ["fast" "safe" "debug"]]
    (let [options {:optimize mode :zig-args ["-lc"]
                   :modules {"native" "native.zig" "bridge" "bridge.zig"}
                   :module-zig-args {"native" ["-Ofast"] "bridge" ["-Osafe"]}}
          normalized (development-options options)]
      (is (= mode (:optimize normalized)))
      (is (= ["-lc" "-ferror-tracing"] (:zig-args normalized)))
      (is (= ["-Ofast"]
             (get-in normalized [:module-zig-args "native"])))
      (is (= ["-Osafe"]
             (get-in normalized [:module-zig-args "bridge"])))
      (is (not= (artifact/compiler-options-identity options)
                (artifact/compiler-options-identity normalized))
          "Native-library hash input includes the new root and module flag profile")
      (is (= normalized (development-options normalized))
          "Normalization must not continually change compiler/cache identity")))
  (let [compatible {:optimize "safe" :zig-args ["-lc" "-ferror-tracing" "-funwind-tables"]
                    :modules {"native" "native.zig"}
                    :module-zig-args {"native" ["-Ofast"]}}]
    (is (identical? compatible (development-options compatible))
        "Already-traced JVM images keep their exact prior compiler/cache identity")))

(deftest reloadable-tracing-conflicts-fail-closed-without-overriding-user-flags
  (doseq [options [{:zig-args ["-fno-error-tracing"]}
                   {:zig-args ["-fno-error-tracing" "-ferror-tracing"]}
                   {:modules {"native" "native.zig"}
                    :module-zig-args {"native" ["-fno-error-tracing"]}}]]
    (let [failure (try (development-options options) nil
                       (catch clojure.lang.ExceptionInfo error (ex-data error)))]
      (is (= :development-dispatch-abi (:aguafria/phase failure)))
      (is (= :conflicting-error-tracing (:reason failure)))))
  (let [options {:modules {"active" "active.zig"}
                 :module-zig-args {"unused" ["-fno-error-tracing"]}}
        normalized (development-options options)]
    (is (= ["-fno-error-tracing"] (get-in normalized [:module-zig-args "unused"])))
    (is (nil? (get-in normalized [:module-zig-args "active"])))
    (is (nil? (:zig-args options)))
    (is (nil? (get-in options [:module-zig-args "active"])))
    (is (not= options normalized)
        "The normalized compiler options must invalidate old artifact identity")))

(deftest only-development-dependency-plans-normalize-the-dispatch-profile
  ;; Process-main hosts and regular native/JVM generations all enter this
  ;; development-dependency seam. Standalone builds use transitive dependencies
  ;; only, while static inspection enters neither mode.
  (let [calls (atom [])
        normalize (ns-resolve 'aguafria.zig.runtime 'development-dispatch-abi-options)
        options {:zig-args ["-fno-error-tracing"] :modules {} :optimize "fast"
                 :reloadable? true}]
    (is (some? normalize))
    (when normalize
      (with-redefs-fn
        {normalize (fn [options] (swap! calls conj options) options)}
        (fn []
          (doseq [static-options [options
                                  (assoc options :transitive-dependencies? true)
                                  (assoc options :development-dependencies? true :reloadable? false)]]
            (let [planned (#'runtime/compiler-options-for-declarations static-options [])]
              (is (= "fast" (:optimize planned)))
              (is (= ["-fno-error-tracing"] (:zig-args planned)))))
          (is (empty? @calls))
          (#'runtime/compiler-options-for-declarations
           (assoc options :development-dependencies? true) [])
          (is (= 1 (count @calls))))))))

(defn- native-handle [lookup name result arguments]
  (.downcallHandle (Linker/nativeLinker)
                   (.orElseThrow (.find ^SymbolLookup lookup name))
                   (FunctionDescriptor/of result (into-array MemoryLayout arguments))
                   (make-array java.lang.foreign.Linker$Option 0)))

(deftest mixed-safe-jvm-and-fast-native-images-dispatch-real-zig-values
  (let [directory (.toFile (Files/createTempDirectory
                            "aguafria-dispatch-profile-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        source (io/file (io/resource "fixtures/jvm/DispatchProfile.zig"))
        root-source (io/file (io/resource "fixtures/jvm/DispatchProfileRoot.zig"))
        zig (runtime/zig-executable)
        profiles (mapv (fn [{:keys [name mode imported?]}]
                         (let [options (development-options
                                        {:optimize mode :zig-args [] :modules {}})
                               library (io/file directory (System/mapLibraryName name))
                               command (vec (concat [zig "build-lib" "-dynamic"
                                                     (str "-O" (:optimize options))]
                                                    (:zig-args options)
                                                    [(str "-femit-bin=" library)]
                                                    (if imported?
                                                      ["--dep" "child" (str "-Mroot=" root-source)
                                                       "-Ofast" (str "-Mchild=" source)]
                                                      [(str source)])))
                               result (apply shell/sh command)]
                           (is (zero? (:exit result)) (pr-str (assoc result :command command)))
                           (when-not (zero? (:exit result))
                             (throw (ex-info "Dispatch profile fixture did not compile"
                                             (assoc result :command command))))
                           {:mode name :library library :command command}))
                       [{:name "fast" :mode "fast"}
                        {:name "safe" :mode "safe"}
                        {:name "imported-fast" :mode "safe" :imported? true}])]
    (with-open [arena (Arena/ofConfined)]
      (let [images (mapv (fn [{:keys [library] :as profile}]
                           (assoc profile :lookup
                                  (SymbolLookup/libraryLookup (.toPath library) arena)))
                         profiles)]
        (doseq [image images]
          (let [probe (native-handle (:lookup image) "optimization_mode" ValueLayout/JAVA_INT [])]
            (is (= ({"fast" 1 "safe" 2 "imported-fast" 1} (:mode image))
                   (.invokeWithArguments probe (ArrayList.)))
                "The compiler confirms each image/module retains its requested optimization")))
        (doseq [caller images target images
                [prefix expected] [["scalar" 42] ["shape" 99]]]
          (testing (str (:mode caller) " caller -> " (:mode target) " target / " prefix)
            (let [getter (native-handle (:lookup target) (str prefix "_address")
                                        ValueLayout/JAVA_LONG [])
                  probe (native-handle (:lookup caller) (str prefix "_probe")
                                       ValueLayout/JAVA_INT [ValueLayout/JAVA_LONG])
                  address (.invokeWithArguments getter (ArrayList.))]
              (is (pos? address))
              (is (= expected (.invokeWithArguments probe (ArrayList. [address])))))))))))
