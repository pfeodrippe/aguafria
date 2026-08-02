(ns aguafria.zig.convert-test
  (:require [aguafria.zig.convert :as convert]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private sample-root "sample/src/root.zig")
(def ^:private generated-sample-root "sample/clojure/sample/src/root.clj")
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

(deftest converted-source-is-an-ordinary-namespace-test
  (let [{:keys [namespace forms clojure-source report]}
        (convert/convert-file sample-root {:namespace 'sample.live.root})
        top-level-apis (map first forms)]
    (is (= 'sample.live.root namespace))
    (is (= 'ns (ffirst (read-forms clojure-source))))
    (is (= 'sample.live.root (second (first (read-forms clojure-source)))))
    (is (every? #(and (symbol? %) (= "az" (clojure.core/namespace %)))
                top-level-apis))
    (is (zero? (:raw-declaration-count report)))
    (is (not-any? #{'az/defraw} top-level-apis))
    (is (not (str/includes? clojure-source "batch/begin!")))
    (is (not (str/includes? clojure-source "batch/end!")))
    (is (not (str/includes? clojure-source "aguafria.zig.batch")))))

(deftest converted-vars-live-in-the-declared-namespace-test
  (let [result (convert/load-converted! generated-sample-root)
        target (the-ns 'sample.src.root)]
    (is (= 'sample.src.root (:namespace result)))
    (is (false? (:compiled? result)))
    (is (:source-only? result))
    (is (= 5 (:declaration-count result)))
    (doseq [name '[std Io printAnotherMessage add]]
      (let [v (ns-resolve target name)]
        (is (var? v) (str name " should be a Var in sample.src.root"))
        (is (= "sample.src.root"
               (get-in (meta v) [:aguafria/declaration :module])))))))

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
  (let [{:keys [forms report]}
        (convert/convert-file "test/fixtures/name_collisions.zig"
                              {:namespace 'fixture.name-collisions})
        demo (some #(when (= 'demo (-> % second (vary-meta dissoc :doc))) %) forms)
        verification (convert/verify-file
                      "test/fixtures/name_collisions.zig"
                      {:namespace 'fixture.name-collisions})]
    (is (zero? (:raw-declaration-count report)))
    (is (= '[az/defn az/defn az/defn] (mapv first forms)))
    (is demo)
    (is (:success? verification))))

(deftest sample-round-trip-runs-with-zig-test
  (let [verification (convert/verify-file sample-root
                                          {:namespace 'sample.verified.root
                                           :mode :test})]
    (is (:success? verification))
    (is (zero? (:raw-declaration-count verification)))
    (is (zero? (:fallback-count verification)))))

(deftest zig-lexical-leaves-are-structural-test
  (let [path "test/fixtures/lexical_literals.zig"
        {:keys [forms report clojure-source]}
        (convert/convert-file path {:namespace 'fixture.lexical-literals})
        verification (convert/verify-file
                      path {:namespace 'fixture.lexical-literals
                            :mode :ast-check})]
    (is (zero? (:fallback-count report)))
    (is (not (str/includes? clojure-source "(raw")))
    (is (some #(and (seq? %) (= 'number-literal (first %)))
              (tree-seq coll? seq forms)))
    (is (some #(and (seq? %) (= 'multiline-string (first %)))
              (tree-seq coll? seq forms)))
    (is (some #(and (seq? %) (= 'error-value (first %)))
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
    (is (str/includes? clojure-source "(inline-for"))
    (is (str/includes? clojure-source "(for-loop"))
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
    (is (str/includes? clojure-source "(switch"))
    (is (str/includes? clojure-source "(inline-case"))
    (is (str/includes? clojure-source "(case-else"))
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
                 :overwrite? true})
        math-report (some #(when (str/ends-with? (str (:namespace %)) ".math") %)
                          (:files report))
        main-report (some #(when (str/ends-with? (str (:namespace %)) ".main") %)
                          (:files report))
        math-file (:output-path math-report)
        main-file (:output-path main-report)
        main-source (slurp main-file)]
    (is (= 2 (:file-count report)))
    (is (not (str/includes? main-source "az/defimport")))
    (is (str/includes? main-source
                       (str "[" namespace-prefix ".math :as-alias math]")))
    (is (str/includes? main-source "math/double"))
    (is (str/includes? main-source ":aguafria/zig-imports"))
    (convert/load-converted! math-file)
    (convert/load-converted! main-file)
    (let [_ (runtime/recompile! (:namespace main-report))
          _ (runtime/await! (:namespace main-report))
          compiled (runtime/module-info (:namespace main-report))
          zig-source (runtime/source (:namespace main-report))]
      (is (some? (:published-generation compiled)))
      (is (some? (:library-path compiled)))
      (is (str/includes? zig-source "const math = @import(\"math.zig\");"))
      (is (str/includes? zig-source "math.double(math.double(value))")))))

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

(deftest nested-zig-containers-are-structural-test
  (let [path "test/fixtures/container.zig"
        {:keys [report clojure-source]}
        (convert/convert-file path {:namespace 'fixture.container})
        verification (convert/verify-file
                      path {:namespace 'fixture.container
                            :mode :build-obj})]
    (is (zero? (:fallback-count report)))
    (is (not (str/includes? clojure-source "(raw")))
    (is (str/includes? clojure-source "(container"))
    (is (str/includes? clojure-source "(enum-field-decl"))
    (is (str/includes? clojure-source "(fn-decl"))
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
    (is (str/includes? clojure-source "(slice-sentinel"))
    (is (str/includes? clojure-source "ak/bit-xor"))
    (is (str/includes? clojure-source "(op \"-%\""))
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
    (is (str/includes? clojure-source "(if-capture"))
    (is (str/includes? clojure-source "(if-capture-stmt"))
    (is (str/includes? clojure-source "(catch-capture"))
    (is (str/includes? clojure-source "(while-loop"))
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
        loaded (convert/load-tree! tiger-root)]
    (testing "the pinned complete corpus was structurally converted"
      (is (= 245 (:file-count report)))
      (is (= 3944 (:declaration-count report)))
      (is (= 3944 (:structural-declaration-count report)))
      (is (zero? (:raw-declaration-count report)))
      (is (zero? (:fallback-count report)))
      (is (every? #(not (re-find raw-boundary-pattern (slurp %)))
                  (->> (file-seq (io/file tiger-root))
                       (filter #(.isFile ^java.io.File %))
                       (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))))))
    (testing "all checked-in files load like normal Clojure namespaces"
      (is (= 245 (:file-count loaded)))
      (is (= 3944 (:declaration-count loaded)))
      (is (every? :source-only? (:files loaded)))
      (is (every? #(= (:namespace %)
                       (some-> (:namespace %) find-ns ns-name))
                  (:files loaded))))
    (testing "storage declarations are Vars in tigerbeetle.src.storage"
      (let [storage (the-ns 'tigerbeetle.src.storage)]
        (doseq [name '[std constants stdx vsr]]
          (is (var? (ns-resolve storage name))
              (str name " should be interned in tigerbeetle.src.storage")))))))

(deftest complete-tigerbeetle-conversion-rejects-raw-boundaries-test
  (let [output (.toFile
                (java.nio.file.Files/createTempDirectory
                 "aguafria-tigerbeetle-structural"
                 (make-array java.nio.file.attribute.FileAttribute 0)))
        report (convert/convert-tree!
                "vendor/tigerbeetle" output
                {:namespace-prefix 'tigerbeetle
                 :overwrite? true})]
    (is (= 245 (:file-count report)))
    (is (= 3944 (:declaration-count report)))
    (is (= (:declaration-count report)
           (:structural-declaration-count report)))
    (is (zero? (:raw-declaration-count report)))
    (is (zero? (:fallback-count report)))
    (is (every? #(not (re-find raw-boundary-pattern (slurp %)))
                (->> (file-seq output)
                     (filter #(.isFile ^java.io.File %))
                     (filter #(str/ends-with? (.getName ^java.io.File %) ".clj")))))))
