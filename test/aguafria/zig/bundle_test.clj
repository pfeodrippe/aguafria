(ns aguafria.zig.bundle-test
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]
            [aguafria.zig.bundle :as bundle]
            [aguafria.zig.explain :as explain]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.precompile :as precompile]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker MemoryLayout SymbolLookup ValueLayout]
           [java.nio.file Files]
           [java.util ArrayList]))

(deftest repeated-handlers-are-validated-once-per-preparation
  (let [configuration (runtime/configuration)
        cache (str (Files/createTempDirectory "aguafria-validation-reuse-"
                                             (make-array java.nio.file.attribute.FileAttribute 0)))
        events (atom [])
        prepare (fn [type]
                  (jvm/precompile-call! {:function 'aguafria.keyword/+
                                        :args [type type]}))]
    (try
      (runtime/configure! {:cache-dir cache})
      (binding [runtime/*compile-only?* true
                bundle/*preparing* (atom {})
                explain/*reporter* #(swap! events conj %)]
        (is (= :prepared (:status (prepare :i32))))
        (is (some #(= :validated (:event %)) @events))
        (let [artifacts (count (:artifacts @bundle/*preparing*))]
          (reset! events [])
          (is (= :prepared (:status (prepare :i32))))
          (is (not-any? #(#{:validated :compiled} (:event %)) @events))
          (is (some #(= :preparation-cache-hit (:event %)) @events))
          (is (= artifacts (count (:artifacts @bundle/*preparing*))))
          (reset! events [])
          (is (= :prepared (:status (prepare :i64))))
          (is (some #(= :validated (:event %)) @events)))
        (doseq [_ (range 2)]
          (reset! events [])
          (is (thrown? Exception
                       (jvm/precompile-call! {:function 'aguafria.keyword//
                                              :args [:i32 :i32]})))
          (is (some #(= :compile-failed (:event %)) @events))))
      (finally (runtime/configure! configuration)))))

(deftest incompatible-configurations-do-not-silently-create-multiple-libraries
  (let [records [{:module "aguafria.jvm.first" :command ["zig"]
                 :groups [{:flags ["-ODebug"]}]}
                {:module "aguafria.jvm.second" :command ["zig"]
                 :groups [{:flags ["-OReleaseSafe"]}]}]]
    (with-redefs [bundle/candidate identity]
      (let [failure (try
                      (bundle/finish! "unused" (atom {:artifacts (zipmap (range) records)}) {})
                      nil
                      (catch clojure.lang.ExceptionInfo error (ex-data error)))]
        (is (= :incompatible-bundle-configurations (:reason failure)))
        (is (= 2 (count (:configurations failure))))))))

(deftest response-arguments-use-zig-quoting-not-shell-quoting
  (let [encode #'bundle/response-argument]
    (is (= "\"path with spaces\"" (encode "path with spaces")))
    (is (= "\"C:\\cache\\module.zig\"" (encode "C:\\cache\\module.zig")))
    (is (= "\"ends\\\\\"" (encode "ends\\")))
    (is (= "\"a\\\"b\"" (encode "a\"b")))
    (is (thrown? clojure.lang.ExceptionInfo (encode "can't-encode")))))

(deftest native-link-options-remain-global-and-include-options-remain-per-module
  (let [parse #'bundle/module-groups
        base ["zig" "build-lib" "-dynamic" "-femit-bin=unused"]
        graph (parse {:command (into base ["-OReleaseFast" "-lc" "-framework" "CoreAudio"
                                          "-I/headers/root" "--dep" "child" "-Mroot=root.zig"
                                          "-I/headers/child" "-Mchild=child.zig"])})]
    (is (= ["-lc" "-framework" "CoreAudio"] (:link-args graph)))
    (is (= [["-OReleaseFast" "-I/headers/root"] ["-I/headers/child"]]
           (mapv :flags (:groups graph))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (parse {:command (into base ["-framework"])})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (parse {:command (into base ["-unknown-option" "-Mroot=root.zig"])})))))

(deftest release-fast-c-import-and-external-library-handlers-share-one-bundle
  (let [directory (.toFile (Files/createTempDirectory "aguafria native bundle "
                                                      (make-array java.nio.file.attribute.FileAttribute 0)))
        cache (str (io/file directory "cache"))
        zig (runtime/zig-executable)
        support-source (io/file directory "support.zig")
        support (io/file directory (System/mapLibraryName "support"))
        external-source (io/file directory "external.zig")
        external (io/file directory (System/mapLibraryName "external"))
        callbacks {:run-command (fn [command cwd]
                                  (apply shell/sh (concat command [:dir cwd])))
                   :preserve-debug! (fn [& _])}]
    (spit support-source "export fn support() void {}\n")
    (spit external-source "export fn fixture_value() i32 { return 40; }\n")
    (spit (io/file directory "fixture.h") "#define FIXTURE_INCREMENT 2\n")
    (doseq [[source library] [[support-source support] [external-source external]]]
      (let [result (shell/sh zig "build-lib" "-dynamic" "-OReleaseFast"
                             (str "-femit-bin=" library) (str source))]
        (is (zero? (:exit result)) (pr-str result))))
    (let [artifacts
          (mapv (fn [n]
                  (let [source (io/file directory (str "handler_" n ".zig"))]
                    (spit source (str "// Aguafria development loader.\n"
                                      "const c = @cImport(@cInclude(\"fixture.h\"));\n"
                                      "extern fn fixture_value() i32;\n"
                                      "export fn __aguafria_probe() i32 { return fixture_value() + c.FIXTURE_INCREMENT + " n "; }\n"))
                    {:module (str "aguafria.jvm.external-bundle-test-" n)
                     :hash (str n)
                     :development-panic :shared
                     :development-panic-support-path (str support)
                     :command [zig "build-lib" "-dynamic" "-femit-bin=unused"
                               (str support) "-OReleaseFast" "-lc" (str external)
                               (str "-I" directory) (str "-Mroot=" source)]}))
                (range 2))
          result (bundle/finish! cache (atom {:artifacts (zipmap (range) artifacts)}) callbacks)
          entries (mapv #(bundle/find-artifact cache %) artifacts)]
      (is (= 1 (count (:packs result))))
      (is (= 2 (:packed-handlers result)))
      (is (= 1 (count (set (map :library entries)))))
      (with-open [arena (Arena/ofConfined)]
        (let [lookup (SymbolLookup/libraryLookup (.toPath (io/file (:library (first entries)))) arena)]
          (doseq [[n entry] (map-indexed vector entries)]
            (let [handle (.downcallHandle (Linker/nativeLinker)
                                         (.get (.find lookup (str (:prefix entry) "__aguafria_probe")))
                                         (FunctionDescriptor/of ValueLayout/JAVA_INT (make-array MemoryLayout 0))
                                         (make-array java.lang.foreign.Linker$Option 0))]
              (is (= (+ 42 n) (.invokeWithArguments handle (ArrayList.)))))))))))

(deftest more-than-64-handlers-and-existing-packs-become-one-real-library
  (let [directory (.toFile (Files/createTempDirectory "aguafria single bundle "
                                                      (make-array java.nio.file.attribute.FileAttribute 0)))
        cache (str (io/file directory "cache"))
        zig (runtime/zig-executable)
        support-source (io/file directory "support.zig")
        support (io/file directory (System/mapLibraryName "support"))
        commands (atom [])
        run! (fn [command cwd]
               (swap! commands conj command)
               (apply shell/sh (concat command [:dir cwd])))
        callbacks {:run-command run! :preserve-debug! (fn [& _])}
        artifacts (mapv (fn [n]
                          (let [source (io/file directory (str "handler_" n ".zig"))]
                            (spit source (str "// Aguafria development loader.\n"
                                              "export fn __aguafria_probe() i32 { return " n "; }\n"))
                            {:module (str "aguafria.jvm.bundle-test-" n)
                             :hash (str n) :development-panic :shared
                             :development-panic-support-path (str support)
                             :command [zig "build-lib" "-dynamic" "-femit-bin=unused"
                                       (str support) "-OReleaseSafe" (str "-Mroot=" source)]}))
                        (range 65))
        collect #(atom {:artifacts (into {} (map (juxt :module identity)) %)})]
    (spit support-source "export fn bundle_test_support() void {}\n")
    (let [built (shell/sh zig "build-lib" "-dynamic" "-OReleaseSafe"
                          (str "-femit-bin=" support) (str support-source))]
      (is (zero? (:exit built)) (pr-str built)))
    ;; Two earlier, legitimate preparation requests must be consolidated when
    ;; their handlers are subsequently requested together.
    (bundle/finish! cache (collect (take 32 artifacts)) callbacks)
    (bundle/finish! cache (collect (drop 32 artifacts)) callbacks)
    (let [cached (mapv #(assoc % :bundle (bundle/find-artifact cache %)) artifacts)
          result (bundle/finish! cache (collect cached) callbacks)
          entries (mapv #(bundle/find-artifact cache %) artifacts)]
      (is (= 1 (count (:packs result))))
      (is (= 65 (:packed-handlers result)))
      (is (= 65 (:reused-handlers result)))
      (is (= 1 (count (set (map :library entries)))))
      (with-open [arena (Arena/ofConfined)]
        (let [lookup (SymbolLookup/libraryLookup (.toPath (io/file (:library (first entries)))) arena)]
          (doseq [[n entry] (map-indexed vector entries)]
            (let [handle (.downcallHandle (Linker/nativeLinker)
                                          (.get (.find lookup (str (:prefix entry) "__aguafria_probe")))
                                          (FunctionDescriptor/of ValueLayout/JAVA_INT (make-array MemoryLayout 0))
                                          (make-array java.lang.foreign.Linker$Option 0))]
              (is (= n (.invokeWithArguments handle (ArrayList.))))))))
      (let [before (count @commands)
            repeated (bundle/finish! cache (collect cached) callbacks)]
        (is (= before (count @commands)))
        (is (= 1 (count (:packs repeated))))
        (is (true? (get-in repeated [:packs 0 :cached?])))))))

(deftest isolation-renames-identifiers-not-zig-source-text
  (let [rename (ns-resolve 'aguafria.zig.bundle 'rename-identifiers)
        exports (ns-resolve 'aguafria.zig.bundle 'exported-names)
        source (str "export fn __aguafria_a() void { __aguafria_a(); }\n"
                    "// __aguafria_a\nconst text = \"__aguafria_a\";\n"
                    "const multiline = \\\\__aguafria_a\n;\n"
                    "const @\"__aguafria_a\" = 1;\n")]
    (is (= ["__aguafria_a"] (exports source)))
    (is (= (str "export fn pack_a() void { pack_a(); }\n"
                "// __aguafria_a\nconst text = \"__aguafria_a\";\n"
                "const multiline = \\\\__aguafria_a\n;\n"
                "const @\"__aguafria_a\" = 1;\n")
           (rename source {"__aguafria_a" "pack_a"})))
    (is (thrown? clojure.lang.ExceptionInfo (exports "export fn application() void {}")))
    (is (thrown? clojure.lang.ExceptionInfo (exports "comptime { @export(&f, .{.name = \"f\"}); }")))))

(deftest invalid-index-is-a-cache-miss
  (let [directory (str (Files/createTempDirectory "aguafria-bundle-index-"
                                                  (make-array java.nio.file.attribute.FileAttribute 0)))
        artifact {:module "aguafria.jvm.test" :hash "abc"}
        index ((ns-resolve 'aguafria.zig.bundle 'index-file) directory artifact)]
    (is (nil? (bundle/find-artifact directory artifact)))
    (io/make-parents index)
    (spit index "not valid edn {")
    (is (nil? (bundle/find-artifact directory artifact)))
    (spit index (pr-str {:version 2 :artifact "wrong" :id "../../elsewhere"}))
    (is (nil? (bundle/find-artifact directory artifact)))))

(deftest relative-assets-are-not-relocated-silently
  (let [validate (ns-resolve 'aguafria.zig.bundle 'validate-imports!)]
    (is (nil? (validate {:source "const x = @import(\"std\"); const y = @import(\"local\");"
                         :deps ["local=some.module"]})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (validate {:source "const x = @import(\"neighbor.zig\");" :deps []})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (validate {:source "const x = @embedFile(\"data.txt\");" :deps []})))))

(defn phase! [cache action]
  (runtime/configure! {:cache-dir cache})
  (let [events (atom [])
        result
        (binding [explain/*reporter* #(swap! events conj %)]
          (case action
            :prepare
            (let [fail! (fn [& _] (throw (ex-info "Preparation invoked native code" {})))]
              (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                (:bundles (precompile/precompile!
                           {:coercions [:i32]
                            :calls [{:function 'aguafria.keyword/+ :args [:i32 :i32]}
                                    {:function 'aguafria.keyword/== :args [:i32 :i32]}
                                    {:function 'aguafria.std.debug/assert :args [:bool]}]
                            :report-file (str cache "/report.edn")}))))
            :run
            (with-open [x (k/i32 10) y (k/i32 20) sum (k/+ x y)]
              (assert (= 30 (az/value sum)))
              (let [panic (try (debug/assert (k/== x y)) nil
                               (catch clojure.lang.ExceptionInfo error (ex-data error)))]
                (assert (= :native-panic (:aguafria/phase panic)) (pr-str panic)))
              (with-open [again (k/+ x y)] (assert (= 30 (az/value again)))))
            :miss
            (with-open [x (k/i32 10) y (k/i32 20) product (k/* x y)]
              (assert (= 200 (az/value product))))
            :changed-mode
            (do
              (runtime/configure! {:jvm-optimize "Debug"})
              (with-open [x (k/i32 10) y (k/i32 20) sum (k/+ x y)]
                (assert (= 30 (az/value sum)))))))]
    {:result result :events (frequencies (map :event @events))}))

(defn- child! [cache action]
  (let [code `(do (require 'aguafria.zig.bundle-test)
                  (prn (phase! ~cache ~action)) (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED" "-cp"
                         (System/getProperty "java.class.path") "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result)) (throw (ex-info "Bundle child failed" result)))
    (edn/read-string (:out result))))

(deftest precompile-publishes-restartable-packs-and-runtime-misses
  (let [cache (str (Files/createTempDirectory "aguafria-bundle-native-"
                                              (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (child! cache :prepare)
        standalone #(filter (fn [file]
                              (and (.isFile file) (str/ends-with? (.getName file) ".dylib")
                                   (str/includes? (str file) "/aguafria_jvm_")))
                            (file-seq (io/file cache)))]
    (is (pos? (get-in prepared [:result :packed-handlers])))
    (is (= 1 (count (get-in prepared [:result :packs]))))
    (is (empty? (standalone)) "AOT should not publish duplicate standalone libraries")
    (let [run (child! cache :run)]
      (is (zero? (get-in run [:events :compiled] 0)))
      (is (pos? (get-in run [:events :bundle-cache-hit] 0)))
      (is (= 1 (get-in run [:events :bundle-loaded]))))
    (let [miss (child! cache :miss)
          restart (child! cache :miss)]
      (is (pos? (get-in miss [:events :compiled] 0)))
      (is (seq (standalone)))
      (is (zero? (get-in restart [:events :compiled] 0)))
      (is (pos? (get-in restart [:events :disk-cache-hit] 0))))
    (let [repeat (child! cache :prepare)]
      (is (= (get-in prepared [:result :packed-handlers])
             (get-in repeat [:result :packed-handlers])))
      (is (= 1 (count (get-in repeat [:result :packs]))))
      (is (true? (get-in repeat [:result :packs 0 :cached?])))
      (is (pos? (get-in repeat [:result :reused-handlers]))))
    (let [changed (child! cache :changed-mode)]
      (is (pos? (get-in changed [:events :compiled] 0)))
      (is (zero? (get-in changed [:events :bundle-cache-hit] 0))))))
