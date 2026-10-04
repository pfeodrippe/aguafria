(ns aguafria.zig.bundle-test
  (:require [aguafria.c :as c]
            [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]
            [aguafria.zig.bundle :as bundle]
            [aguafria.zig.explain :as explain]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.precompile :as precompile]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker MemoryLayout SymbolLookup ValueLayout]
           [java.nio.file Files]
           [java.util ArrayList]))

(deftest standalone-exclusions-identify-exact-artifacts
  (let [first-id (#'bundle/artifact-id {:module "aguafria.jvm.fixture" :hash "first"})
        second-id (#'bundle/artifact-id {:module "aguafria.jvm.fixture" :hash "second"})
        exclusion {:module "aguafria.jvm.fixture" :reason :external-exports}
        collected (atom {:excluded {first-id exclusion second-id exclusion}})
        result (bundle/finish! "unused" collected {})]
    (is (not= first-id second-id))
    (is (= #{first-id second-id} (set (map :artifact-id (:standalone result)))))
    (is (= [exclusion exclusion] (mapv #(dissoc % :artifact-id) (:standalone result))))))

(deftest bundle-materialization-keeps-source-on-disk-and-validates-its-identity
  (let [directory (.toFile (Files/createTempDirectory "aguafria-bundle-sources-"
                                                      (make-array java.nio.file.attribute.FileAttribute 0)))
        source-file (io/file directory "module.zig")
        source (str "export fn __aguafria_fixture() void {}\n"
                    (apply str (repeat 1000 "// Shared module source.\n")))
        artifact {:module "aguafria.jvm.fixture" :hash "fixture"
                  :development-panic :shared
                  :development-panic-support-path "support.dylib"
                  :command ["zig" "build-lib" "-dynamic" "-femit-bin=unused"
                            "support.dylib" "-Osafe" (str "-Mroot=" source-file)]}]
    (spit source-file source)
    (let [candidate (bundle/candidate artifact)
          output (io/file directory "materialized")
          entry (#'bundle/materialize-entry! output candidate)
          group (first (:groups entry))]
      (is (some? candidate))
      (is (not-any? #(contains? % :source) (:groups candidate)))
      (is (every? #(re-matches #"[a-f0-9]{64}" (:source-key %)) (:groups candidate)))
      (is (not-any? #(contains? % :source) (:groups entry)))
      (is (= (str/replace source "__aguafria_fixture"
                          (str (:prefix entry) "__aguafria_fixture"))
             (slurp (:new-path group))))
      (spit source-file (str source "// Changed after preparation.\n"))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Prepared bundle source changed before linking"
           (#'bundle/materialize-entry! (io/file directory "changed") candidate))))))

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

(deftest shared-source-analysis-is-reused-without-caching-eligibility
  (let [directory (.toFile (Files/createTempDirectory "aguafria-bundle-scan-"
                                                      (make-array java.nio.file.attribute.FileAttribute 0)))
        root (io/file directory "root.zig")
        dependency (io/file directory "dependency.zig")
        root-source "const dependency = @import(\"dependency\");\nexport fn __aguafria_fixture() void {}\n"
        source "pub const value: u32 = 1;\n"
        artifact {:module "aguafria.jvm.fixture" :hash "fixture"
                  :development-panic :shared
                  :development-panic-support-path "support.dylib"
                  :command ["zig" "build-lib" "-dynamic" "-femit-bin=unused"
                            "support.dylib" "-Osafe" "--dep" "dependency"
                            (str "-Mroot=" root) (str "-Mdependency=" dependency)]}
        analyze @#'bundle/analyze-source
        scans (atom [])]
    (spit root root-source)
    (spit dependency source)
    (with-redefs-fn {#'bundle/analyze-source (fn [source]
                                               (swap! scans conj source)
                                               (analyze source))}
      (fn []
        (binding [bundle/*preparing* (atom {})]
          (let [first-candidate (bundle/candidate artifact)
                repeated (mapv deref (repeatedly 8 #(future (bundle/candidate artifact))))]
            (is (some? first-candidate))
            (is (every? #(= first-candidate %) repeated))
            (is (= 2 (count @scans)))
            (is (= 2 (count (:source-analyses @bundle/*preparing*))))
            (is (not-any? #(or (contains? % :source) (contains? % :tokens))
                          (vals (:source-analyses @bundle/*preparing*))))
            (let [missing-dependency (assoc artifact :command
                                            ["zig" "build-lib" "-dynamic" "-femit-bin=unused"
                                             "support.dylib" "-Osafe"
                                             (str "-Mroot=" root) (str "-Mdependency=" dependency)])]
              (is (nil? (bundle/candidate missing-dependency)))
              (is (= :relative-or-dynamic-assets
                     (:reason (first (vals (:excluded @bundle/*preparing*))))))
              (is (= 2 (count @scans))))
            (let [modified (java.nio.file.Files/getLastModifiedTime
                            (.toPath dependency) (make-array java.nio.file.LinkOption 0))]
              (spit dependency "pub const value: u32 = 2;\n")
              (java.nio.file.Files/setLastModifiedTime (.toPath dependency) modified)
              (let [changed (bundle/candidate artifact)]
                (is (some? changed))
                (is (not= (get-in first-candidate [:groups 1 :source-key])
                          (get-in changed [:groups 1 :source-key])))
                (is (= 3 (count @scans)))))))))))

(deftest incompatible-configurations-do-not-silently-create-multiple-libraries
  (let [records [{:module "aguafria.jvm.first" :command ["zig"]
                  :groups [{:flags ["-ODebug"]}]}
                 {:module "aguafria.jvm.second" :command ["zig"]
                  :groups [{:flags ["-Osafe"]}]}]]
    (with-redefs [bundle/candidate identity]
      (let [failure (try
                      (bundle/finish! "unused" (atom {:artifacts (zipmap (range) records)}) {})
                      nil
                      (catch clojure.lang.ExceptionInfo error (ex-data error)))]
        (is (= :incompatible-bundle-configurations (:reason failure)))
        (is (= 2 (count (:configurations failure))))))))

(deftest conflicting-native-link-lists-do-not-share-a-bundle
  (let [records [{:module "aguafria.jvm.first" :command ["zig"]
                  :groups [{:flags ["-Osafe"]}] :link-args ["-lc" "-lm"]}
                 {:module "aguafria.jvm.second" :command ["zig"]
                  :groups [{:flags ["-Osafe"]}] :link-args ["-lc" "-lz"]}]]
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
        graph (parse {:command (into base ["-Ofast" "-lc" "-framework" "CoreAudio"
                                           "-I/headers/root" "--dep" "child" "-Mroot=root.zig"
                                           "-I/headers/child" "-Mchild=child.zig"])})]
    (is (= ["-lc" "-framework" "CoreAudio"] (:link-args graph)))
    (is (= [["-Ofast" "-I/headers/root"] ["-I/headers/child"]]
           (mapv :flags (:groups graph))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (parse {:command (into base ["-framework"])})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (parse {:command (into base ["-unknown-option" "-Mroot=root.zig"])})))))

(deftest appended-native-dependencies-and-translated-c-share-one-bundle
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
    (spit (io/file directory "fixture.zig")
          (slurp (:translated-zig-path
                  (c/translate-zig! (io/file directory "fixture.h")
                                    {:cache-dir (str (io/file directory "c-cache"))}))))
    (doseq [[source library] [[support-source support] [external-source external]]]
      (let [result (shell/sh zig "build-lib" "-dynamic" "-Ofast"
                             (str "-femit-bin=" library) (str source))]
        (is (zero? (:exit result)) (pr-str result))))
    (let [artifacts
          (mapv (fn [n]
                  (let [source (io/file directory (str "handler_" n ".zig"))]
                    (spit source (str "// Aguafria development loader.\n"
                                      "const c = @import(\"fixture\");\n"
                                      (when (pos? n) "extern fn fixture_value() i32;\n")
                                      "export fn __aguafria_probe() i32 { return "
                                      (if (zero? n) "40" "fixture_value()")
                                      " + c.FIXTURE_INCREMENT + " n "; }\n"))
                    {:module (str "aguafria.jvm.external-bundle-test-" n)
                     :hash (str n)
                     :development-panic :shared
                     :development-panic-support-path (str support)
                     :command (vec (concat [zig "build-lib" "-dynamic" "-femit-bin=unused"
                                            (str support) "-Ofast" "-lc"]
                                           (when (pos? n) [(str external)])
                                           [(str "-I" directory) "--dep" "fixture"
                                            (str "-Mroot=" source)
                                            (str "-Mfixture=" (io/file directory "fixture.zig"))]))}))
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
                                       (str support) "-Osafe" (str "-Mroot=" source)]}))
                        (range 65))
        collect #(atom {:artifacts (into {} (map (juxt :module identity)) %)})]
    (spit support-source "export fn bundle_test_support() void {}\n")
    (let [built (shell/sh zig "build-lib" "-dynamic" "-Osafe"
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
        analyze (ns-resolve 'aguafria.zig.bundle 'analyze-source)
        validate (ns-resolve 'aguafria.zig.bundle 'validate-source!)
        source (str "export fn __aguafria_a() void { __aguafria_a(); }\n"
                    "// __aguafria_a\nconst text = \"__aguafria_a\";\n"
                    "const multiline = \\\\__aguafria_a\n;\n"
                    "const @\"__aguafria_a\" = 1;\n")]
    (is (= ["__aguafria_a"] (:exports (analyze source))))
    (is (= (str "export fn pack_a() void { pack_a(); }\n"
                "// __aguafria_a\nconst text = \"__aguafria_a\";\n"
                "const multiline = \\\\__aguafria_a\n;\n"
                "const @\"__aguafria_a\" = 1;\n")
           (rename source {"__aguafria_a" "pack_a"})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (validate {:deps [] :analysis (analyze "export fn application() void {}")})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (validate {:deps [] :analysis
                            (analyze "comptime { @export(&f, .{.name = \"f\"}); }")})))))

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
  (let [analyze (ns-resolve 'aguafria.zig.bundle 'analyze-source)
        validate (ns-resolve 'aguafria.zig.bundle 'validate-source!)]
    (is (nil? (validate {:analysis (analyze "const x = @import(\"std\"); const y = @import(\"local\");")
                         :deps ["local=some.module"]})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (validate {:analysis (analyze "const x = @import(\"neighbor.zig\");") :deps []})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (validate {:analysis (analyze "const x = @embedFile(\"data.txt\");") :deps []})))))

(defn phase! [cache action]
  (runtime/configure! {:cache-dir cache})
  (let [events (atom [])
        result
        (binding [explain/*reporter* #(swap! events conj %)]
          (case action
            (:prepare :prepare-limited-printer :standalone-prepare)
            (let [fail! (fn [& _] (throw (ex-info "Preparation invoked native code" {})))
                  prepare! (fn []
                             (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                               (:bundles (precompile/precompile!
                                          {:coercions [:i32]
                                           :bundle? (not= :standalone-prepare action)
                                           :calls [{:function 'aguafria.keyword/+ :args [:i32 :i32]}
                                                   {:function 'aguafria.keyword/== :args [:i32 :i32]}
                                                   {:function 'aguafria.std.debug/assert :args [:bool]}]
                                           :report-file (str cache "/report.edn")}))))]
              (if (= :prepare-limited-printer action)
                (binding [*print-length* 1 *print-level* 1 *print-meta* true
                          *print-dup* true *print-readably* false *print-namespace-maps* false]
                  (prepare!))
                (prepare!)))
            (:run :run-other-values :run-limited-printer)
            (let [[left right] (if (= :run action) [10 20] [4 9])
                  check! (fn []
                           (with-open [x (k/i32 left) y (k/i32 right) sum (k/+ x y)]
                             (assert (= (+ left right) (a/value sum)))
                             (let [panic (try (debug/assert (k/== x y)) nil
                                              (catch clojure.lang.ExceptionInfo error (ex-data error)))]
                               (assert (= :native-panic (:aguafria/phase panic)) (pr-str panic)))
                             (with-open [again (k/+ x y)] (assert (= (+ left right) (a/value again))))))]
              (if (= :run-limited-printer action)
                (binding [*print-length* 1 *print-level* 1 *print-meta* true
                          *print-dup* true *print-readably* false *print-namespace-maps* false]
                  (check!))
                (check!)))
            :miss
            (with-open [x (k/i32 10) y (k/i32 20) product (k/* x y)]
              (assert (= 200 (a/value product))))
            :changed-mode
            (do
              (runtime/configure! {:jvm-optimize "debug"})
              (with-open [x (k/i32 10) y (k/i32 20) sum (k/+ x y)]
                (assert (= 30 (a/value sum)))))
            :type-prepare
            (let [fail! (fn [& _] (throw (ex-info "Preparation invoked native code" {})))
                  report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                           (precompile/precompile!
                            {:analyze ['aguafria.zig.discovery-type-expression-fixture]
                             :report-file (str cache "/type-report.edn")}))
                  operations (filter #(= 'aguafria.zig/type (:function %))
                                     (get-in report [:analysis 0 :operations]))]
              (assert (= 4 (count operations)))
              (assert (every? #(= :prepared (:status %)) (mapcat :handlers operations)))
              (:bundles report))
            :type-run
            (do
              (require 'aguafria.zig.discovery-type-expression-fixture)
              (let [point (var-get (resolve 'aguafria.zig.discovery-type-expression-fixture/Point))
                    types [(a/type :u32) (a/type point) (a/type [:array 7 point])
                           (let [n 5] (a/type [:array n :u16]))]]
                (assert (every? value/zig-type? types))
                (assert (= "u32" (:zig-name (value/type-info (first types)))))
                (assert (str/starts-with? (:zig-name (value/type-info (nth types 2))) "[7]"))
                (assert (= "[5]u16" (:zig-name (value/type-info (last types)))))
                (mapv (fn [type] (a/value (k/sizeOf type))) types)))
            :constructor-prepare
            (let [fail! (fn [& _] (throw (ex-info "Preparation invoked native code" {})))]
              (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                (:bundles
                 (precompile/precompile!
                  {:analyze ['aguafria.zig.discovery-constructor-closure-fixture]
                   :report-file (str cache "/constructor-report.edn")}))))
            :constructor-run
            (do
              (require 'aguafria.zig.discovery-constructor-closure-fixture)
              (with-open [text (k/as "bonjour ☔" [:slice-const :u8])
                          aliased-text (k/as "hi" (var-get (resolve 'aguafria.zig.discovery-constructor-closure-fixture/Text)))
                          flag (k/var false :bool)
                          scratch (k/var k/undefined [:array 7 :i32])
                          text-length ((resolve 'aguafria.zig.discovery-constructor-closure-fixture/text-length) text)
                          aliased-length ((resolve 'aguafria.zig.discovery-constructor-closure-fixture/aliased-text-length)
                                          aliased-text)
                          scratch-length ((resolve 'aguafria.zig.discovery-constructor-closure-fixture/scratch-length)
                                          (k/& scratch))]
                [(a/value text-length) (a/value scratch-length) (a/value aliased-length)
                 (a/value flag)]))
            :callback-prepare
            (let [fail! (fn [& _] (throw (ex-info "Preparation invoked native code" {})))]
              (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                (:bundles
                 (precompile/precompile!
                  {:analyze ['aguafria.zig.discovery-callback-fixture]
                   :coercions [:usize]
                   :report-file (str cache "/callback-report.edn")}))))
            :callback-run
            (do
              (require 'aguafria.zig.discovery-callback-fixture)
              (mapv (fn [input]
                      (with-open [argument (k/usize input)
                                  handle (:ok ((resolve 'aguafria.std.Thread/spawn)
                                               {:stack_size 1048576}
                                               (var-get (resolve 'aguafria.zig.discovery-callback-fixture/worker))
                                               [argument]))]
                        ((a/field handle :join))
                        (a/value ((resolve 'aguafria.zig.discovery-callback-fixture/read-observed)))))
                    [11 23]))
            :namespace-config-prepare
            (let [fail! (fn [& _] (throw (ex-info "Preparation invoked native code" {})))]
              (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                (:bundles
                 (precompile/precompile!
                  {:analyze ['aguafria.zig.precompile-configuration-b-fixture
                             'aguafria.zig.precompile-configuration-a-fixture]
                   :report-file (str cache "/namespace-config-report.edn")}))))
            :scalar-profile-prepare
            (let [fail! (fn [& _] (throw (ex-info "Preparation invoked native code" {})))]
              (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                (:bundles
                 (precompile/precompile!
                  {:analyze ['aguafria.zig.precompile-scalar-profile-fixture
                             'aguafria.zig.precompile-configuration-a-fixture]
                   :report-file (str cache "/scalar-profile-report.edn")}))))
            :scalar-profile-run
            (let [call (fn [namespace input]
                         (require namespace)
                         (with-open [result ((ns-resolve namespace 'increment) input)]
                           (a/value result)))]
              [(with-open [result (k/u64 99)] (a/value result))
               (call 'aguafria.zig.precompile-configuration-a-fixture 11)
               (call 'aguafria.zig.precompile-scalar-profile-fixture 23)
               (call 'aguafria.zig.precompile-configuration-a-fixture 31)])
            :namespace-config-run
            (mapv (fn [[namespace input]]
                    (require namespace)
                    (with-open [argument (k/i32 input)
                                result ((ns-resolve namespace 'increment) argument)
                                sum (k/+ argument 1)]
                      [(a/value result) (a/value sum)]))
                  [['aguafria.zig.precompile-configuration-a-fixture 11]
                   ['aguafria.zig.precompile-configuration-b-fixture 23]])
            :link-growth-prepare
            (binding [runtime/*compile-only?* true
                      bundle/*preparing* (atom {})]
              (let [fail! (fn [& _] (throw (ex-info "Preparation invoked native code" {})))]
                (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                  (jvm/precompile-coercion! :i32)
                  (runtime/configure! {:zig-args ["-lc"]})
                  (jvm/precompile-coercion! :i32)
                  (runtime/finish-precompile-bundles! bundle/*preparing*))))
            :link-growth-run
            (let [first-value (with-open [x (k/i32 12)] (a/value x))]
              (runtime/configure! {:zig-args ["-lc"]})
              (with-open [x (k/i32 34)] [first-value (a/value x)]))))]
    {:result result :events (frequencies (map :event @events))
     :compiled (into [] (comp (filter #(= :compiled (:event %)))
                              (map #(select-keys % [:module :artifact-key]))) @events)
     :artifact-keys (into #{} (keep :artifact-key) @events)
     :bundle-keys (into #{} (comp (filter #(= :bundle-cache-hit (:event %)))
                                  (map :artifact-key)) @events)
     :libraries (into #{} (comp (filter #(.isFile %))
                                (filter #(some (fn [suffix] (str/ends-with? (.getName %) suffix))
                                               [".dylib" ".so" ".dll"]))
                                (map str))
                      (file-seq (io/file cache)))}))

(defn- child! [cache action]
  (let [code `(do (require 'aguafria.zig.bundle-test)
                  (prn (phase! ~cache ~action)) (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED" "-cp"
                         (System/getProperty "java.class.path") "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result)) (throw (ex-info "Bundle child failed" result)))
    (edn/read-string (:out result))))

(deftest appended-native-dependencies-reuse-one-pack-after-restart
  (let [cache (str (Files/createTempDirectory "aguafria-link-growth-"
                                              (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (child! cache :link-growth-prepare)
        run (child! cache :link-growth-run)]
    (is (= 1 (count (get-in prepared [:result :packs]))))
    (is (pos? (get-in prepared [:result :packed-handlers])))
    (is (= [12 34] (:result run)))
    (is (zero? (get-in run [:events :compiled] 0)))
    (is (= 1 (get-in run [:events :bundle-loaded])))
    (is (seq (:bundle-keys run)))
    (is (every? (:artifact-keys prepared) (:bundle-keys run)))
    (is (= (:libraries prepared) (:libraries run)))))

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
      (is (= 1 (get-in run [:events :bundle-loaded])))
      (is (seq (:bundle-keys run)))
      (is (every? (:artifact-keys prepared) (:bundle-keys run))
          "Runtime must request the exact artifact keys recorded by preparation")
      (is (empty? (standalone)) "A bundle hit must not create a standalone library"))
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

(deftest aot-is-preferred-to-existing-standalone-artifacts
  (let [cache (str (Files/createTempDirectory "aguafria-bundle-precedence-"
                                              (make-array java.nio.file.attribute.FileAttribute 0)))
        standalone (child! cache :standalone-prepare)
        prepared (child! cache :prepare)
        restarted (child! cache :run-other-values)]
    (is (pos? (get-in standalone [:events :compiled] 0)))
    (is (= 1 (count (get-in prepared [:result :packs]))))
    (is (pos? (get-in prepared [:result :packed-handlers])))
    (is (zero? (get-in restarted [:events :compiled] 0)))
    (is (pos? (get-in restarted [:events :bundle-cache-hit] 0)))
    (is (= 1 (get-in restarted [:events :bundle-loaded])))
    (is (seq (:bundle-keys restarted)))
    (is (every? (:artifact-keys prepared) (:bundle-keys restarted)))
    (is (= (:libraries prepared) (:libraries restarted))
        "Changing runtime values must reuse AOT without publishing another dylib")))

(deftest repl-print-settings-reuse-the-prepared-artifact-keys
  (doseq [action [:prepare :prepare-limited-printer]]
    (let [cache (str (Files/createTempDirectory "aguafria-printer-bundle-"
                                                (make-array java.nio.file.attribute.FileAttribute 0)))
          prepared (child! cache action)
          restarted (child! cache :run-limited-printer)]
      (is (= 1 (count (get-in prepared [:result :packs]))))
      (is (pos? (get-in prepared [:result :packed-handlers])))
      (is (zero? (get-in restarted [:events :compiled] 0)) (pr-str restarted))
      (is (pos? (get-in restarted [:events :bundle-cache-hit] 0)))
      (is (= 1 (get-in restarted [:events :bundle-loaded])))
      (is (seq (:bundle-keys restarted)))
      (is (every? (:artifact-keys prepared) (:bundle-keys restarted)))
      (is (= (:libraries prepared) (:libraries restarted))
          "REPL printer preferences must not publish another native library"))))

(deftest compiler-observed-type-expressions-reuse-aot-after-restart
  (let [cache (str (Files/createTempDirectory "aguafria-type-bundle-"
                                              (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (child! cache :type-prepare)
        restarted (child! cache :type-run)]
    (is (= 1 (count (get-in prepared [:result :packs]))))
    (is (pos? (get-in prepared [:result :packed-handlers])))
    (is (= [4 8 56 10] (:result restarted)))
    (is (zero? (get-in restarted [:events :compiled] 0)) (pr-str restarted))
    (is (pos? (get-in restarted [:events :bundle-cache-hit] 0)))
    (is (= 1 (get-in restarted [:events :bundle-loaded])))
    (is (= (:libraries prepared) (:libraries restarted))
        "Ordinary type queries must not create additional native libraries")))

(deftest constructors-for-declared-operands-and-mutable-literals-reuse-aot
  (let [cache (str (Files/createTempDirectory "aguafria-constructor-bundle-"
                                              (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (child! cache :constructor-prepare)
        restarted (child! cache :constructor-run)]
    (is (= 1 (count (get-in prepared [:result :packs]))))
    (is (pos? (get-in prepared [:result :packed-handlers])))
    (is (= [11 7 2 false] (:result restarted)))
    (is (zero? (get-in restarted [:events :compiled] 0)) (pr-str restarted))
    (is (pos? (get-in restarted [:events :bundle-cache-hit] 0)))
    (is (= 1 (get-in restarted [:events :bundle-loaded])))
    (is (every? (:artifact-keys prepared) (:bundle-keys restarted)))
    (is (= (:libraries prepared) (:libraries restarted))
        "Typed JVM operands and mutable undefined must reuse the prepared bundle")))

(deftest named-native-callbacks-reuse-aot-after-restart
  (let [cache (str (Files/createTempDirectory "aguafria-callback-bundle-"
                                              (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (child! cache :callback-prepare)
        restarted (child! cache :callback-run)]
    (is (= 1 (count (get-in prepared [:result :packs]))))
    (is (pos? (get-in prepared [:result :packed-handlers])))
    (is (= [11 23] (:result restarted)))
    (is (zero? (get-in restarted [:events :compiled] 0)) (pr-str restarted))
    (is (pos? (get-in restarted [:events :bundle-cache-hit] 0)))
    (is (= 1 (get-in restarted [:events :bundle-loaded])))
    (is (every? (:artifact-keys prepared) (:bundle-keys restarted)))
    (is (= (:libraries prepared) (:libraries restarted))
        "Spawning native callbacks must not publish duplicate handler libraries")))

(deftest namespace-load-configurations-reuse-one-pack-after-restart
  (let [cache (str (Files/createTempDirectory "aguafria-namespace-config-bundle-"
                                              (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (child! cache :namespace-config-prepare)
        restarted (child! cache :namespace-config-run)]
    (is (= 1 (count (get-in prepared [:result :packs]))))
    (is (pos? (get-in prepared [:result :packed-handlers])))
    (is (= [[12 12] [24 24]] (:result restarted)))
    (is (zero? (get-in restarted [:events :compiled] 0)) (pr-str restarted))
    (is (pos? (get-in restarted [:events :bundle-cache-hit] 0)))
    (is (= 1 (get-in restarted [:events :bundle-loaded])))
    (is (every? (:artifact-keys prepared) (:bundle-keys restarted)))
    (is (= (:libraries prepared) (:libraries restarted))
        "Later namespace link settings must not replace earlier preparation keys")))

(deftest scalar-results-reuse-aot-across-namespace-load-profiles
  (let [cache (str (Files/createTempDirectory "aguafria-scalar-profiles-"
                                              (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (child! cache :scalar-profile-prepare)
        restarted (child! cache :scalar-profile-run)]
    (is (= 1 (count (get-in prepared [:result :packs]))))
    (is (= [99 12 24 32] (:result restarted)))
    (is (zero? (get-in restarted [:events :compiled] 0)) (pr-str restarted))
    (is (pos? (get-in restarted [:events :bundle-cache-hit] 0)))
    (is (= 1 (get-in restarted [:events :bundle-loaded])))
    (is (seq (:bundle-keys restarted)))
    (is (every? (:artifact-keys prepared) (:bundle-keys restarted)))
    (is (= (:libraries prepared) (:libraries restarted))
        "A prior callee's scalar storage must reuse AOT after a later namespace adds links")))
