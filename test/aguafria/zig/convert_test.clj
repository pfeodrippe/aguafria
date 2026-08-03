(ns aguafria.zig.convert-test
  (:require [aguafria.zig.convert :as convert]
            [aguafria.zig.project :as project]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private tiger-root "generated/tigerbeetle")
(def ^:private tiger-report "generated/tigerbeetle-report.edn")
(def ^:private raw-boundary-pattern
  #"\((?:az/defraw|raw|raw-chunks|raw-statements|raw-statement-chunks)(?:\s|\))")

(defn- read-forms
  [source]
  (with-open [reader (java.io.PushbackReader. (java.io.StringReader. source))]
    (loop [forms []]
      (let [form (read {:eof ::eof} reader)]
        (if (= ::eof form)
          forms
          (recur (conj forms form)))))))

(deftest converted-source-has-compact-attrs-and-spacing-test
  (let [{container-source :clojure-source}
        (convert/convert-file "test/fixtures/container.zig"
                              {:namespace 'fixture.compact-source})]
    (doseq [obsolete [":zig/order" ":zig/leading" ":zig/trailing"
                      ":export false" ":public false"
                      ":implicit-return false" ":source-comment false"]]
      (is (not (str/includes? container-source obsolete)) obsolete))
    (is (str/includes? container-source ":attrs #{:public}"))
    (is (str/includes? container-source ":attrs #{:enum}"))
    (is (not (str/includes? container-source ":attrs #{}")))
    (is (str/includes? container-source "(az/field-decl replica Replica)"))
    (is (re-find #"\)\n\n\(az/defconst" container-source))))

(deftest empty-converted-module-still-loads-its-own-namespace-test
  (let [directory (.toFile
                   (java.nio.file.Files/createTempDirectory
                    "aguafria-empty-conversion"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        output (io/file directory "empty.clj")]
    (convert/convert-file! "test/fixtures/empty.zig" output
                           {:namespace 'fixture.empty
                            :overwrite? true})
    (let [result (convert/load-converted! output)]
      (is (= 'fixture.empty (:namespace result)))
      (is (find-ns 'fixture.empty))
      (is (zero? (:declaration-count result)))
      (is (:source-only? result)))))

(deftest structural-operator-name-collisions-are-real-zig-references-test
  (let [{:keys [forms report clojure-source]}
        (convert/convert-file "test/fixtures/name_collisions.zig"
                              {:namespace 'fixture.name-collisions})
        demo (some #(when (= 'demo (-> % second (vary-meta dissoc :doc))) %) forms)
        verification (convert/verify-file
                      "test/fixtures/name_collisions.zig"
                      {:namespace 'fixture.name-collisions})]
    (is (zero? (:raw-declaration-count report)))
    (is (= '[az/defn az/defn az/defn az/defn] (mapv first forms)))
    (is (= 'assert (second (nth forms 2))))
    (is (not (str/includes? clojure-source "assert-zig")))
    (is demo)
    (is (:success? verification))))

(deftest zig-lexical-leaves-are-structural-test
  (let [path "test/fixtures/lexical_literals.zig"
        {:keys [forms report clojure-source]}
        (convert/convert-file path {:namespace 'fixture.lexical-literals})
        verification (convert/verify-file
                      path {:namespace 'fixture.lexical-literals
                            :mode :ast-check})]
    (is (zero? (:fallback-count report)))
    (is (not (str/includes? clojure-source "(raw")))
    (is (some #(and (seq? %) (= 'az/number-literal (first %)))
              (tree-seq coll? seq forms)))
    (is (some #(and (seq? %) (= 'az/multiline-string (first %)))
              (tree-seq coll? seq forms)))
    (is (some #(and (seq? %) (= 'az/error-value (first %)))
              (tree-seq coll? seq forms)))
    (is (:success? verification))))

(deftest zig-for-and-errdefer-are-structural-test
  (let [path "test/fixtures/control_flow.zig"
        {:keys [forms report clojure-source]}
        (convert/convert-file path {:namespace 'fixture.control-flow})
        verification (convert/verify-file
                      path {:namespace 'fixture.control-flow
                            :mode :build-obj})]
    (is (zero? (:fallback-count report)))
    (is (not (str/includes? clojure-source "(raw")))
    (is (str/includes? clojure-source "pointer-capture"))
    (is (str/includes? clojure-source "else-clause"))
    (is (str/includes? clojure-source "else-expression"))
    (is (str/includes? clojure-source "(az/inline-for"))
    (is (str/includes? clojure-source "(az/for-loop"))
    (is (str/includes? clojure-source "(az/while-loop"))
    (is (re-find #"\(ak/errdefer \(az/block\)\)\n\n  \(ak/var total"
                 clojure-source))
    (is (re-find #"\)\n\n  \(for\n" clojure-source))
    (is (str/includes? clojure-source ":inline? true"))
    (is (:success? verification))))

(deftest zig-switch-is-structural-test
  (let [path "test/fixtures/switch.zig"
        {:keys [report clojure-source]}
        (convert/convert-file path {:namespace 'fixture.switch})
        verification (convert/verify-file
                      path {:namespace 'fixture.switch
                            :mode :build-obj})]
    (is (zero? (:fallback-count report)))
    (is (not (str/includes? clojure-source "(raw")))
    (is (str/includes? clojure-source "(ak/switch"))
    (is (str/includes? clojure-source "(az/inline-case"))
    (is (str/includes? clojure-source "(az/case-else"))
    (is (:success? verification))))

(deftest converted-relative-imports-are-normal-requires-test
  (let [output (.toFile
                (java.nio.file.Files/createTempDirectory
                 "aguafria-import-tree"
                 (make-array java.nio.file.attribute.FileAttribute 0)))
        namespace-prefix (symbol (str "fixture.import-tree-" (gensym)))
        report (convert/convert-tree!
                "test/fixtures/import_tree" output
                {:namespace-prefix namespace-prefix
                 :overwrite? true
                 :cache-dir (.getAbsolutePath
                             (io/file output ".conversion-cache"))})
        repeat-report
        (convert/convert-tree!
         "test/fixtures/import_tree" output
         {:namespace-prefix namespace-prefix
          :overwrite? true
          :cache-dir (.getAbsolutePath
                      (io/file output ".conversion-cache"))})
        math-report (some #(when (str/ends-with? (str (:namespace %)) ".math") %)
                          (:files report))
        main-report (some #(when (str/ends-with? (str (:namespace %)) ".main") %)
                          (:files report))
        math-file (:output-path math-report)
        main-file (:output-path main-report)
        main-source (slurp main-file)]
    (is (= 2 (:file-count report)))
    (is (zero? (:conversion-cache-hit-count report)))
    (is (= 2 (:conversion-cache-hit-count repeat-report)))
    (is (not (str/includes? main-source "az/defimport")))
    (is (str/includes? main-source
                       (str "[" namespace-prefix ".math :as math]")))
    (is (not (re-find #"\(az/defconst\s+math\b" main-source)))
    (is (not (str/includes? main-source "math.zig")))
    (is (str/includes? main-source "math/double"))
    (is (not (str/includes? main-source ":aguafria/zig-imports")))
    ;; The generated namespace uses an ordinary Clojure require. The EDN
    ;; catalog alone retains the original Zig import spelling so standalone
    ;; materialization can recreate the source graph without the input tree.
    (is (str/includes? (slurp (:catalog-path report)) "math.zig"))
    (is (str/includes? (slurp (:catalog-path report)) ":require-mode :as"))
    ;; `main.clj` sorts before `math.clj`, so this proves a freshly generated
    ;; root outside deps.edn is visible to eager dependency requires.
    (let [loaded (convert/load-tree! output)]
      (is (= 2 (:file-count loaded)))
      (is (= 2 (:declaration-count loaded))))
    (is (var? (ns-resolve (the-ns (:namespace math-report)) 'double)))
    (let [_ (runtime/recompile! (:namespace main-report))
          _ (runtime/await! (:namespace main-report))
          compiled (runtime/module-info (:namespace main-report))
          zig-source (runtime/source (:namespace main-report))]
      (is (some? (:published-generation compiled)))
      (is (some? (:library-path compiled)))
      (is (str/includes? zig-source
                         (str "const math = @import(\""
                              (:namespace math-report) "\");")))
      (is (str/includes? zig-source "math.double(math.double(value))")))))

(deftest build-generated-option-modules-are-captured-and-used-test
  (testing "Zig configure data becomes self-contained EDN and needs no manual module path"
    (let [input "test/fixtures/build_options_project"
          graph (convert/build-generated-modules input)
          alternate-graph
          (convert/build-generated-modules input {:build-steps ["alternate"]})
          output (.toFile
                  (java.nio.file.Files/createTempDirectory
                   "aguafria-build-options"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
          namespace-prefix
          (symbol (str "fixture.build-options-" (random-uuid)))
          old-config (runtime/configuration)
          report (convert/convert-tree!
                  input output
                  {:namespace-prefix namespace-prefix
                   :overwrite? true})
          root-report
          (some #(when (= "src/root.zig" (:relative-path %)) %) (:files report))
          catalog (edn/read-string (slurp (:catalog-path report)))
          captured-module
          (get-in catalog [:modules (str (:namespace root-report))
                           :generated-modules "build_options"])
          captured-source (:source-template captured-module)]
      (try
        (is (= 1 (:module-count graph)))
        (is (= 2 (:path-value-count graph)))
        (is (zero? (:conflict-count graph)))
        (is (zero? (:conflict-count alternate-graph)))
        (is (str/includes?
             (get-in alternate-graph
                     [:modules-by-path "src/root.zig" "build_options"])
             "pub const answer: u32 = 99;"))
        (is (= 1 (:generated-module-count report)))
        (is (= 2 (:generated-module-path-value-count report)))
        (is (= 2 (:generated-module-bundled-path-count report)))
        (is (= 2 (:generated-module-bundled-file-count report)))
        (is (str/includes? captured-source "pub const answer: u32 = 42;"))
        (is (str/includes? captured-source
                           "pub const message: []const u8 = \"captured by Zig\";"))
        (is (str/includes? captured-source "__AGUAFRIA_BUILD_PATH_"))
        (is (not (str/includes? captured-source
                                (.getCanonicalPath (io/file input)))))
        (is (.isFile
             (io/file output
                      (:bundle-relative (first (:paths captured-module))))))
        (is (true? (:executable?
                    (first
                     (:entries
                      (some #(when (= "tool_path" (:name %)) %)
                            (:paths captured-module)))))))
        (runtime/configure! {:async? false :modules {}})
        (convert/load-converted! (:output-path root-report))
        (runtime/recompile! (:namespace root-report))
        (let [answer (ns-resolve (the-ns (:namespace root-report)) 'answer)
              data-path-length
              (ns-resolve (the-ns (:namespace root-report)) 'data_path_length)
              tool-path-length
              (ns-resolve (the-ns (:namespace root-report)) 'tool_path_length)
              value (answer)
              info (runtime/stats (:namespace root-report))
              module-info (runtime/module-info (:namespace root-report))
              resolved-source
              (get (project/generated-modules (:namespace root-report))
                   "build_options")]
          (is (= 42 value))
          (is (= (count
                  (.getCanonicalPath
                   (io/file output
                            (:bundle-relative
                             (some #(when (= "data_path" (:name %)) %)
                                   (:paths captured-module))))))
                 (data-path-length)))
          (is (= (count
                  (.getCanonicalPath
                   (io/file output
                            (:bundle-relative
                             (some #(when (= "tool_path" (:name %)) %)
                                   (:paths captured-module))))))
                 (tool-path-length)))
          (is (str/includes? resolved-source (.getAbsolutePath output)))
          (is (not (str/includes? resolved-source
                                  (.getCanonicalPath (io/file input)))))
          (is (= :finished (get-in info [:last-build :status])))
          (is (some? (:published-generation info)))
          (is (some #(str/starts-with? % "-Mbuild_options=")
                    (:command module-info)))
          (is (= 1 (count (filter #{"build_options"}
                                  (:command module-info))))
              "named build modules should be dependencies only of importers"))
        (finally
          (runtime/configure! old-config)
          (when root-report (remove-ns (:namespace root-report))))))))

(deftest compiler-provided-import-is-an-ordinary-module-var-test
  (let [path "test/fixtures/compiler_import.zig"
        {:keys [forms report clojure-source]}
        (convert/convert-file path {:namespace 'fixture.compiler-import})
        verification (convert/verify-file
                      path {:namespace 'fixture.compiler-import
                            :mode :build-obj})]
    (is (zero? (:fallback-count report)))
    (is (not (str/includes? clojure-source "az/defimport")))
    (is (str/includes? clojure-source "(ak/import \"builtin\")"))
    (is (= 'az/defconst (ffirst forms)))
    (is (= 'builtin (second (first forms))))
    (is (:success? verification))))

(deftest struct-literal-field-order-is-explicit-test
  (let [{:keys [clojure-source zig-source]}
        (convert/render-zig "test/fixtures/ordered_object.zig"
                            {:namespace 'fixture.ordered-object})]
    (is (str/includes? clojure-source "(az/object"))
    (is (not (str/includes? clojure-source "{:z 1")))
    (is (str/includes?
         zig-source
         ".{.z = 1, .a = 2, .y = 3, .b = 4, .x = 5, .c = 6, .w = 7, .d = 8, .v = 9, .e = 10}"))))

(deftest materialized-project-preserves-relative-imports-and-compiles-test
  (let [generated (.toFile
                   (java.nio.file.Files/createTempDirectory
                    "aguafria-materialized-clojure"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        project-parent (.toFile
                        (java.nio.file.Files/createTempDirectory
                         "aguafria-materialized-zig-parent"
                         (make-array java.nio.file.attribute.FileAttribute 0)))
        project (io/file project-parent "new-project")
        report (convert/convert-tree!
                "test/fixtures/import_tree" generated
                {:namespace-prefix (symbol (str "fixture.materialized-" (gensym)))
                 :overwrite? true
                 :bundle-assets? true})
        independent-report
        (assoc report :input-root
               (.getAbsolutePath (io/file generated "missing-original-project")))
        materialized (convert/materialize-project! independent-report project)
        main-source (slurp (io/file project "main.zig"))
        project-note (slurp (io/file project "project-note.txt"))
        result (shell/sh "zig" "build-obj" "main.zig"
                         :dir (.getAbsolutePath project))
        unchanged (convert/materialize-project! independent-report project)]
    (is (.isDirectory project))
    (is (= 2 (:zig-file-count materialized)))
    (is (= 1 (:asset-file-count materialized)))
    (is (= 3 (:written-count materialized)))
    (is (str/includes? main-source "const math = @import(\"math.zig\");"))
    (is (str/includes? main-source "math.double(math.double(value))"))
    (is (zero? (:exit (shell/sh "zig" "fmt" "--check" "."
                                :dir (.getAbsolutePath project)))))
    (is (= "This non-Zig asset must survive independent Aguafria materialization.\n"
           project-note))
    (is (zero? (:exit result)) (:err result))
    (is (zero? (:written-count unchanged)))
    (is (= 3 (:unchanged-count unchanged)))))

(deftest nested-zig-containers-are-structural-test
  (let [path "test/fixtures/container.zig"
        {:keys [forms report clojure-source]}
        (convert/convert-file path {:namespace 'fixture.container})
        verification (convert/verify-file
                      path {:namespace 'fixture.container
                            :mode :build-obj})]
    (is (zero? (:fallback-count report)))
    (is (not (str/includes? clojure-source "(raw")))
    (is (str/includes? clojure-source "(az/container"))
    (is (str/includes? clojure-source "(az/enum-field-decl"))
    (is (str/includes? clojure-source "(az/fn-decl"))
    (is (str/includes? clojure-source ":zig/name \"@\\\"127.0.0.1\\\"\""))
    (is (str/includes? clojure-source ":zig/name \"@\\\"null-device\\\"\""))
    (is (str/includes? clojure-source "^{:zig/name \"init\"} zig-init-"))
    (is (not-any? nil? (tree-seq coll? seq forms)))
    (is (:success? verification))))

(deftest error-sets-unions-and-qualified-pointers-are-structural-test
  (let [path "test/fixtures/types.zig"
        {:keys [report clojure-source]}
        (convert/convert-file path {:namespace 'fixture.types})
        verification (convert/verify-file
                      path {:namespace 'fixture.types
                            :mode :build-obj})]
    (is (zero? (:fallback-count report)))
    (is (not (str/includes? clojure-source "(raw")))
    (is (str/includes? clojure-source ":error-set"))
    (is (str/includes? clojure-source ":error-union"))
    (is (str/includes? clojure-source ":pointer"))
    (is (str/includes? clojure-source ":fn"))
    (is (str/includes? clojure-source ":callconv"))
    (is (str/includes? clojure-source ":array-sentinel"))
    (is (str/includes? clojure-source "(az/slice-sentinel"))
    (is (str/includes? clojure-source "ak/bit-xor"))
    (is (str/includes? clojure-source "(az/op \"-%\""))
    (is (:success? verification))))

(deftest if-and-while-captures-are-structural-test
  (let [path "test/fixtures/captures.zig"
        {:keys [report clojure-source]}
        (convert/convert-file path {:namespace 'fixture.captures})
        verification (convert/verify-file
                      path {:namespace 'fixture.captures
                            :mode :build-obj})]
    (is (zero? (:fallback-count report)))
    (is (not (str/includes? clojure-source "(raw")))
    (is (str/includes? clojure-source "(az/if-capture"))
    (is (str/includes? clojure-source "(az/if-capture-stmt"))
    (is (str/includes? clojure-source "(az/catch-capture"))
    (is (str/includes? clojure-source "(az/while-loop"))
    (is (str/includes? clojure-source ":continue"))
    (is (str/includes? clojure-source ":error"))
    (is (:success? verification))))

(deftest destructuring-assignments-are-structural-test
  (let [path "test/fixtures/destructure.zig"
        {:keys [report clojure-source]}
        (convert/convert-file path {:namespace 'fixture.destructure})
        verification (convert/verify-file
                      path {:namespace 'fixture.destructure
                            :mode :build-obj})]
    (is (zero? (:fallback-count report)))
    (is (not (str/includes? clojure-source "(raw")))
    (is (str/includes? clojure-source "(destructure"))
    (is (str/includes? clojure-source ":discard"))
    (is (str/includes? clojure-source ":target"))
    (is (:success? verification))))

(deftest checked-tigerbeetle-corpus-is-plain-and-loadable-test
  (let [report (edn/read-string (slurp tiger-report))
        catalog (edn/read-string
                 (slurp (io/file tiger-root "aguafria-project.edn")))
        vopr-generated
        (get-in catalog [:modules "tigerbeetle.src.vopr"
                         :generated-modules])
        loaded (convert/load-tree! tiger-root)]
    (testing "the pinned complete corpus was structurally converted"
      (is (= 245 (:file-count report)))
      (is (= 4032 (:declaration-count report)))
      (is (= 4032 (:structural-declaration-count report)))
      (is (zero? (:raw-declaration-count report)))
      (is (zero? (:fallback-count report)))
      (is (zero? (:unresolved-syntax-count report)))
      (is (= [[] ["vopr"]] (:build-profiles report)))
      (is (= #{"vsr_options" "vsr_vopr_options"}
             (set (keys vopr-generated))))
      (let [source (get vopr-generated "vsr_vopr_options")]
        (is (str/includes? (if (map? source)
                            (:source-template source)
                            source)
                           "accounting")))
      (is (every? #(zero? (:unresolved-syntax-count %)) (:files report)))
      (is (every? #(not (re-find raw-boundary-pattern (slurp %)))
                  (->> (file-seq (io/file tiger-root))
                       (filter #(.isFile ^java.io.File %))
                       (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))))))
    (testing "all checked-in files load like normal Clojure namespaces"
      (is (= 245 (:file-count loaded)))
      (is (= 4032 (:declaration-count loaded)))
      (is (every? :source-only? (:files loaded)))
      (is (every? #(= (:namespace %)
                       (some-> (:namespace %) find-ns ns-name))
                  (:files loaded))))
    (testing "storage uses normal aliases and real local declarations"
      (let [storage (the-ns 'tigerbeetle.src.storage)]
        (is (= 'tigerbeetle.src.vsr
               (some-> (get (ns-aliases storage) 'vsr) ns-name)))
        (is (nil? (:aguafria/zig-imports (meta storage))))
        (doseq [name '[constants stdx]]
          (is (var? (ns-resolve storage name))
              (str name " should be interned in tigerbeetle.src.storage")))))))

(deftest generated-tigerbeetle-namespace-loads-directly-in-a-fresh-repl-test
  (let [module 'tigerbeetle.src.message-buffer
        expression
        (pr-str
         `(do
            (require '~module :reload)
            (aguafria.zig.runtime/await! '~module)
            (let [info# (aguafria.zig.runtime/module-info '~module)
                  result# {:module (:module info#)
                           :declaration-count (count (:definitions info#))
                           :published? (some? (:published-generation info#))
                           :pending? (:pending? info#)
                           :source-only? (:source-only? info#)
                           :manual-module-count
                           (count (:modules
                                   (aguafria.zig.runtime/configuration)))
                           :captured-vsr-options?
                           (boolean
                            (some #(clojure.string/starts-with?
                                    % "-Mvsr_options=")
                                  (:command info#)))}]
              (shutdown-agents)
              result#)))
        classpath
        (pr-str
         {:paths (mapv #(.getAbsolutePath (io/file %))
                       ["src" "resources" "generated/tigerbeetle"])})
        result (shell/sh "clojure"
                         "-J--enable-native-access=ALL-UNNAMED"
                         "-Sdeps" classpath "-M" "-e" expression)]
    (is (zero? (:exit result)) (str (:out result) (:err result)))
    (when (zero? (:exit result))
      (is (= {:module (str module)
              :declaration-count 8
              :published? true
              :pending? false
              :source-only? false
              :manual-module-count 0
              :captured-vsr-options? true}
             (edn/read-string (str/trim (:out result))))))))

(deftest generated-tigerbeetle-main-loads-lazily-in-a-fresh-repl-test
  (let [module 'tigerbeetle.src.tigerbeetle.main
        expression
        (pr-str
         `(do
            (require '~module :reload)
            (aguafria.zig.runtime/await! '~module)
            (let [stats# (aguafria.zig.runtime/stats '~module)
                  result# {:status (:status stats#)
                           :declaration-count (:declaration-count stats#)
                           :native-generation-count
                           (:native-generation-count stats#)
                           :dispatch-version-count
                           (:dispatch-version-count stats#)
                           :failed-build-count
                           (get-in (aguafria.zig.runtime/stats)
                                   [:summary :failed-build-count])}]
              (shutdown-agents)
              result#)))
        classpath
        (pr-str
         {:paths (mapv #(.getAbsolutePath (io/file %))
                       ["src" "resources" "generated/tigerbeetle"])})
        result (shell/sh "clojure"
                         "-J--enable-native-access=ALL-UNNAMED"
                         "-Sdeps" classpath "-M" "-e" expression)]
    (is (zero? (:exit result)) (str (:out result) (:err result)))
    (when (zero? (:exit result))
      (is (= {:status :finished
              :declaration-count 38
              :native-generation-count 1
              :dispatch-version-count 8
              :failed-build-count 0}
             (edn/read-string (str/trim (:out result))))))))

(deftest generated-tigerbeetle-main-runs-inside-native-host-test
  (let [module 'tigerbeetle.src.tigerbeetle.main
        result-file (.toFile (java.nio.file.Files/createTempFile
                              "aguafria-tigerbeetle-host" ".edn"
                              (make-array java.nio.file.attribute.FileAttribute 0)))
        expression
        (pr-str
         `(do
            (require '~module :reload)
            (require 'aguafria.zig.host)
            (aguafria.zig.runtime/await! '~module)
            (let [main-var# (ns-resolve '~module '~'main)
                  handle# (aguafria.zig.host/start!
                           main-var# ["version"] {:argv0 "tigerbeetle"})
                  result# (aguafria.zig.host/await! handle#)]
              (spit ~(.getAbsolutePath result-file)
                    (pr-str {:result result#
                             :info (select-keys
                                    (aguafria.zig.host/info handle#)
                                    [:status :share-state? :exit-code])
                             :summary (select-keys
                                       (:summary (aguafria.zig.runtime/stats))
                                       [:native-host-count
                                        :active-native-host-count
                                        :finished-native-host-count])})))
            (shutdown-agents)))
        classpath
        (pr-str
         {:paths (mapv #(.getAbsolutePath (io/file %))
                       ["src" "resources" "generated/tigerbeetle"])})
        result (shell/sh "clojure"
                         "-J--enable-native-access=ALL-UNNAMED"
                         "-Sdeps" classpath "-M" "-e" expression)]
    (is (zero? (:exit result)) (str (:out result) (:err result)))
    (when (zero? (:exit result))
      (is (str/includes? (:out result) "TigerBeetle version"))
      (is (= {:result {:id 1 :status :finished :exit-code 0 :duration-ms 3}
              :info {:status :finished :share-state? true :exit-code 0}
              :summary {:native-host-count 1
                        :active-native-host-count 0
                        :finished-native-host-count 1}}
             (update-in (edn/read-string (slurp result-file))
                        [:result :duration-ms]
                        (constantly 3)))))))

(deftest generated-tigerbeetle-callee-hot-swaps-without-recompiling-caller-test
  (let [module 'tigerbeetle.src.stdx.stdx
        caller-module 'aguafria.tigerbeetle-hot-probe
        expression
        (pr-str
         `(do
            (require '~module :reload)
            (aguafria.zig.runtime/await! '~module)
            (let [caller-ns# (create-ns '~caller-module)]
              (binding [*ns* caller-ns#]
                (alias '~'stdx '~module)
                (eval
                 '~'(aguafria.zig/defn probe :- :bool
                    []
                    (stdx/zeroed (& [0 0 0])))))
              (aguafria.zig.runtime/await! '~caller-module)
              (let [probe# (ns-resolve caller-ns# '~'probe)
                    before-value# (probe#)
                    before-generation#
                    (->> (:declarations
                          (aguafria.zig.runtime/stats '~caller-module))
                         (some (fn [declaration#]
                                 (when (= "probe" (:name declaration#))
                                   (:implementation-generation
                                    declaration#)))))]
                (binding [*ns* (the-ns '~module)]
                  (eval
                   '~'(aguafria.zig/defn zeroed
                      "Checks that a byteslice is zeroed."
                      {:attrs #{:public}}
                      :- :bool
                      [[bytes [:slice-const :u8]]]
                      (aguafria.keyword/return
                       (== (aguafria.zig/field bytes len) 0)))))
                (aguafria.zig.runtime/await! '~module)
                (let [after-generation#
                      (->> (:declarations
                            (aguafria.zig.runtime/stats '~caller-module))
                           (some (fn [declaration#]
                                   (when (= "probe" (:name declaration#))
                                     (:implementation-generation
                                      declaration#)))))
                      result# {:before before-value#
                               :after (probe#)
                               :caller-unchanged?
                               (= before-generation# after-generation#)}]
                  (shutdown-agents)
                  result#)))))
        classpath
        (pr-str
         {:paths (mapv #(.getAbsolutePath (io/file %))
                       ["src" "resources" "generated/tigerbeetle"])})
        result (shell/sh "clojure"
                         "-J--enable-native-access=ALL-UNNAMED"
                         "-Sdeps" classpath "-M" "-e" expression)]
    (is (zero? (:exit result)) (str (:out result) (:err result)))
    (when (zero? (:exit result))
      (is (= {:before true :after false :caller-unchanged? true}
             (edn/read-string (str/trim (:out result))))))))

(deftest generated-tigerbeetle-options-type-hot-reloads-in-a-fresh-repl-test
  (let [module 'tigerbeetle.src.state-machine.workload
        expression
        (pr-str
         `(do
            (require '~module :reload)
            (require 'clojure.walk)
            (aguafria.zig.runtime/await! '~module)
            (let [options-var# (ns-resolve '~module '~'OptionsType)
                  workload-var# (ns-resolve '~module '~'WorkloadType)
                  original# (:aguafria/declaration (meta options-var#))
                  before# (aguafria.zig.runtime/module-info '~module)
                  before-workload-generation#
                  (:generation
                   (last (aguafria.zig.runtime/type-versions workload-var#)))
                  changed#
                  (aguafria.zig.runtime/declaration-info
                   (update
                    original# :body
                    (fn [body#]
                      (clojure.walk/postwalk
                       (fn [value#]
                         (if (= value# [:pending_timeout_mean 1])
                           [:pending_timeout_mean 2]
                           value#))
                       body#))))]
              (aguafria.zig.runtime/register-declaration! changed#)
              (aguafria.zig.runtime/await! '~module)
              (let [after# (aguafria.zig.runtime/module-info '~module)
                    options-version#
                    (last (aguafria.zig.runtime/type-versions options-var#))
                    workload-version#
                    (last (aguafria.zig.runtime/type-versions workload-var#))
                    result#
                    {:generation-advanced?
                     (< (:published-generation before#)
                        (:published-generation after#))
                     :schema-compatible?
                     (= (:schema-fingerprint original#)
                        (:schema-fingerprint changed#)
                        (:schema-fingerprint options-version#))
                     :implementation-changed?
                     (not= (:implementation-fingerprint original#)
                           (:implementation-fingerprint changed#))
                     :options-status (:status options-version#)
                     :workload-dependent-republished?
                     (< before-workload-generation#
                        (:generation workload-version#))
                     :source-changed?
                     (clojure.string/includes? (:source after#)
                                               ".pending_timeout_mean = 2")}]
                (shutdown-agents)
                result#))))
        classpath
        (pr-str
         {:paths (mapv #(.getAbsolutePath (io/file %))
                       ["src" "resources" "generated/tigerbeetle"])})
        result (shell/sh "clojure"
                         "-J--enable-native-access=ALL-UNNAMED"
                         "-Sdeps" classpath "-M" "-e" expression)]
    (is (zero? (:exit result)) (str (:out result) (:err result)))
    (when (zero? (:exit result))
      (is (= {:generation-advanced? true
              :schema-compatible? true
              :implementation-changed? true
              :options-status :compatible
              :workload-dependent-republished? true
              :source-changed? true}
             (edn/read-string (str/trim (:out result))))))))

(deftest generated-tigerbeetle-options-type-breaking-layout-coexists-test
  (let [module 'tigerbeetle.src.state-machine.workload
        expression
        (pr-str
         `(do
            (require '~module :reload)
            (require 'clojure.walk)
            (aguafria.zig.runtime/await! '~module)
            (let [options-var# (ns-resolve '~module '~'OptionsType)
                  workload-var# (ns-resolve '~module '~'WorkloadType)
                  original# (:aguafria/declaration (meta options-var#))
                  old-options-generation#
                  (:generation
                   (first (aguafria.zig.runtime/type-versions options-var#)))
                  changed#
                  (aguafria.zig.runtime/declaration-info
                   (update
                    original# :body
                    (fn [body#]
                      (clojure.walk/postwalk
                       (fn [value#]
                         (if (and (seq? value#)
                                  (symbol? (first value#))
                                  (= "field-decl" (name (first value#)))
                                  (= '~'pending_timeout_mean (second value#))
                                  (= :u32 (last value#)))
                           (apply list (concat (butlast value#) [:u64]))
                           value#))
                       body#))))]
              (aguafria.zig.runtime/register-declaration! changed#)
              (aguafria.zig.runtime/await! '~module)
              (let [options-versions#
                    (aguafria.zig.runtime/type-versions options-var#)
                    workload-before-adoption#
                    (aguafria.zig.runtime/type-versions workload-var#)
                    adoption-registration#
                    (aguafria.zig.runtime/register-declaration!
                     (:aguafria/declaration (meta workload-var#)))
                    adoption-publication#
                    (aguafria.zig.runtime/await! '~module)
                    workload-versions#
                    (aguafria.zig.runtime/type-versions workload-var#)
                    result#
                    {:options-statuses (mapv :status options-versions#)
                     :workload-before-adoption-statuses
                     (mapv :status workload-before-adoption#)
                     :workload-statuses (mapv :status workload-versions#)
                     :options-schemas-distinct?
                     (apply not= (map :schema-fingerprint options-versions#))
                     :workload-schemas-distinct?
                     (apply not= (map :schema-fingerprint workload-versions#))
                     :old-native-generation-retained?
                     (boolean
                      (some #(= old-options-generation# (:generation %))
                            (:native-generations
                             (aguafria.zig.runtime/stats '~module))))}]
                (shutdown-agents)
                result#))))
        classpath
        (pr-str
         {:paths (mapv #(.getAbsolutePath (io/file %))
                       ["src" "resources" "generated/tigerbeetle"])})
        result (shell/sh "clojure"
                         "-J--enable-native-access=ALL-UNNAMED"
                         "-Sdeps" classpath "-M" "-e" expression)]
    (is (zero? (:exit result)) (str (:out result) (:err result)))
    (when (zero? (:exit result))
      (is (= {:options-statuses [:retained :breaking]
              :workload-before-adoption-statuses [:initialized]
              :workload-statuses [:retained :breaking]
              :options-schemas-distinct? true
              :workload-schemas-distinct? true
              :old-native-generation-retained? true}
             (edn/read-string (str/trim (:out result))))))))

(deftest complete-tigerbeetle-conversion-materializes-and-compiles-test
  (let [output (.toFile
                (java.nio.file.Files/createTempDirectory
                 "aguafria-tigerbeetle-structural"
                 (make-array java.nio.file.attribute.FileAttribute 0)))
        project (.toFile
                 (java.nio.file.Files/createTempDirectory
                  "aguafria-tigerbeetle-materialized"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
        report (convert/convert-tree!
                "vendor/tigerbeetle" output
                {:namespace-prefix 'tigerbeetle
                 :overwrite? true
                 :bundle-assets? true})
        independent-report
        (assoc report :input-root
               (.getAbsolutePath (io/file output "missing-original-tigerbeetle")))
        materialized (convert/materialize-project! independent-report project)
        git-result (shell/sh "git" "rev-parse" "--verify" "HEAD"
                             :dir (.getAbsolutePath
                                   (.getCanonicalFile
                                    (io/file "vendor/tigerbeetle"))))
        git-commit (str/trim (:out git-result))
        git-dir-result (shell/sh "git" "rev-parse" "--absolute-git-dir"
                                 :dir (.getAbsolutePath
                                       (.getCanonicalFile
                                        (io/file "vendor/tigerbeetle"))))
        git-dir (str/trim (:out git-dir-result))
        build-env (assoc (into {} (System/getenv))
                         "GIT_DIR" git-dir
                         "GIT_WORK_TREE" (.getAbsolutePath project))
        check-result (shell/sh "zig" "build"
                               (str "-Dgit-commit=" git-commit)
                               "check"
                               :dir (.getAbsolutePath project)
                               :env build-env)
        original-root (.getCanonicalFile (io/file "vendor/tigerbeetle"))
        original-build (shell/sh "zig" "build"
                                 (str "-Dgit-commit=" git-commit)
                                 :dir (.getAbsolutePath original-root))
        converted-build (shell/sh "zig" "build"
                                  (str "-Dgit-commit=" git-commit)
                                  :dir (.getAbsolutePath project)
                                  :env build-env)
        original-executable (.getAbsolutePath
                             (io/file original-root
                                      "zig-out/bin/tigerbeetle"))
        converted-executable (.getAbsolutePath
                              (io/file project
                                       "zig-out/bin/tigerbeetle"))
        original-version (shell/sh original-executable "version")
        converted-version (shell/sh converted-executable "version")
        original-help (shell/sh original-executable "--help")
        converted-help (shell/sh converted-executable "--help")]
    (is (= 245 (:file-count report)))
    (is (= 4032 (:declaration-count report)))
    (is (= (:declaration-count report)
           (:structural-declaration-count report)))
    (is (zero? (:raw-declaration-count report)))
    (is (zero? (:fallback-count report)))
    (is (zero? (:unresolved-syntax-count report)))
    (is (every? #(zero? (:unresolved-syntax-count %)) (:files report)))
    (is (every? #(not (re-find raw-boundary-pattern (slurp %)))
                (->> (file-seq output)
                     (filter #(.isFile ^java.io.File %))
                     (filter #(str/ends-with? (.getName ^java.io.File %) ".clj")))))
    (is (= 245 (:zig-file-count materialized)))
    (is (= 374 (:asset-file-count materialized)))
    (is (= 619 (:file-count materialized)))
    (is (= 4032 (:declaration-count materialized)))
    (is (zero? (:exit git-result)) (:err git-result))
    (is (zero? (:exit git-dir-result)) (:err git-dir-result))
    (is (= 40 (count git-commit)))
    (is (zero? (:exit check-result))
        (str (:out check-result) (:err check-result)))
    (is (zero? (:exit original-build))
        (str (:out original-build) (:err original-build)))
    (is (zero? (:exit converted-build))
        (str (:out converted-build) (:err converted-build)))
    (testing "representative CLI behavior is byte-identical"
      (is (= (select-keys original-version [:exit :out :err])
             (select-keys converted-version [:exit :out :err])))
      (is (= (select-keys original-help [:exit :out :err])
             (select-keys converted-help [:exit :out :err])))
      (is (str/includes? (:out converted-version) (subs git-commit 0 7)))
      (is (str/includes? (:out converted-help) "tigerbeetle start")))))
