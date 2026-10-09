(ns aguafria.zig.bundle-support-sharing-test
  (:require [aguafria.zig.artifact :as artifact]
            [aguafria.zig.bundle :as bundle]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.lang.foreign FunctionDescriptor Linker MemoryLayout ValueLayout]
           [java.nio.file Files]
           [java.util ArrayList]))

(defn- directory []
  (.toFile (Files/createTempDirectory "aguafria-shared-support-"
                                      (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- graph [directory index support proof]
  (let [root (io/file directory (str "root-" index) "module.zig")
        helper (io/file directory (str "helper-" index) "module.zig")
        source (str "const support = @import(\"support\");\n"
                    "export fn __aguafria_probe() i32 { return support.value(); }\n")]
    (io/make-parents root)
    (io/make-parents helper)
    (spit root source)
    (spit helper support)
    {:module (str "aguafria.jvm.support-fixture-" index) :hash (str index)
     :jvm-adapter? true :development-panic :shared
     :development-panic-support-path "support.dylib"
     :compiler-owned-shared-modules (when proof {"support" proof})
     :command ["zig" "build-lib" "-dynamic" "-femit-bin=unused" "support.dylib"
               "-Osafe" "--dep" "support" (str "-Mroot=" root)
               (str "-Msupport=" helper)]}))

(def support-source "const std = @import(\"std\");\npub fn value() i32 { return 42; }\n")

(defn- proof [source]
  {:kind :stateless-jvm-transport :module "support"
   :source-key (artifact/key-for :bundle-source source)})

(deftest only-known-supplier-owned-resources-have-provenance
  (let [source (slurp (io/resource "aguafria/jvm_result.zig"))
        declaration {:module "transport" :kind :raw :name '__aguafria_jvm :code source
                     :compiler-owned-support :stateless-jvm-transport}
        provenance #'runtime/compiler-support-provenance]
    (is (= (assoc (proof source) :module "transport")
           (provenance "transport" [declaration] source)))
    (is (nil? (provenance "transport" [(dissoc declaration :compiler-owned-support)] source)))
    (is (nil? (provenance "transport" [(assoc declaration :code support-source)] support-source)))
    (is (nil? (provenance "transport" [declaration {:kind :var}] source)))
    (is (= (emitter/emit-static-dependency-module "transport" [declaration])
           (emitter/emit-static-dependency-module
            "transport" [(dissoc declaration :compiler-owned-support)])))))

(deftest support-is-written-and-declared-once-with-original-root-graphs
  (let [directory (directory)
        artifacts (mapv #(graph directory % support-source (proof support-source)) (range 3))
        candidates (mapv bundle/candidate artifacts)
        entries (#'bundle/materialize-entries! (io/file directory "pack") candidates)
        groups (#'bundle/unique-compilation-groups entries)
        helpers (mapv #(second (:groups %)) entries)]
    (is (= 4 (count groups)))
    (is (= 1 (count (set (map :new-name helpers)))))
    (is (= 3 (count (set (map :entry entries)))))
    (is (= support-source (slurp (:new-path (first helpers)))))
    (is (every? #(= ["-Osafe"] (:flags (first (:groups %)))) entries))
    (is (every? #(= [(str "support=" (:new-name (first helpers)))]
                    (:new-deps (first (:groups %)))) entries))
    (is (= (mapv #'bundle/artifact-id artifacts) (mapv #'bundle/artifact-id entries)))))

(deftest dependency-slicing-revalidates-support-provenance-for-its-emitted-source
  (let [module "aguafria.jvm.sliced-support-fixture"
        source (slurp (io/resource "aguafria/jvm_result.zig"))
        declaration {:module module :kind :raw :name '__aguafria_jvm
                     :declaration-key [:raw '__aguafria_jvm] :logical-id [module :transport]
                     :code source :compiler-owned-support :stateless-jvm-transport}
        original (emitter/emit-dependency-module module [declaration])
        entry {:module module :source original :dependencies [] :dispatch-entries []
               :state-entries []
               :compiler-support (#'runtime/compiler-support-provenance module [declaration] original)}]
    (with-redefs-fn
      {#'runtime/registry (atom {module {:definitions {(:declaration-key declaration) declaration}}})}
      #(let [sliced (get (#'runtime/compute-linkable-development-dependency-snapshot
                          {module entry} #{(:logical-id declaration)} #{}) module)]
         (is (not= original (:source sliced)))
         (is (= (assoc (proof (:source sliced)) :module module) (:compiler-support sliced)))
         (is (= (:source sliced)
                (:source (get (#'runtime/compute-linkable-development-dependency-snapshot
                               {module (dissoc entry :compiler-support)}
                               #{(:logical-id declaration)} #{}) module))))))))

(deftest matching-user-source-remains-private
  (let [directory (directory)
        candidates (mapv #(bundle/candidate (graph directory % support-source nil)) (range 2))
        entries (#'bundle/materialize-entries! (io/file directory "pack") candidates)]
    (is (= 4 (count (#'bundle/unique-compilation-groups entries))))
    (is (= 2 (count (set (map #(get-in % [:groups 1 :new-name]) entries)))))))

(deftest differing-flags-and-proofs-cannot-share
  (let [directory (directory)
        first-artifact (graph directory 0 support-source (proof support-source))
        second-artifact (graph directory 1 support-source (proof support-source))
        candidates [(bundle/candidate first-artifact)
                    (update-in (bundle/candidate second-artifact) [:groups 1 :flags] conj "-Osmall")]
        entries (#'bundle/materialize-entries! (io/file directory "flags") candidates)
        rejected (bundle/candidate
                  (assoc second-artifact :compiler-owned-shared-modules
                         {"support" (assoc (proof support-source) :source-key "wrong")}))]
    (is (= 4 (count (#'bundle/unique-compilation-groups entries))))
    (is (nil? (get-in rejected [:groups 1 :shared-support])))))

(deftest changed-source-and-inconsistent-shared-inputs-are-rejected
  (let [directory (directory)
        artifact (graph directory 0 support-source (proof support-source))
        candidate (bundle/candidate artifact)
        helper (get-in candidate [:groups 1 :path])]
    (spit helper (str support-source "// Changed after capture\n"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source changed before linking"
                          (#'bundle/materialize-entries! (io/file directory "pack") [candidate])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"inconsistent inputs"
         (#'bundle/unique-compilation-groups
          [{:groups [{:new-name "shared" :source-key "first"}
                     {:new-name "shared" :source-key "changed"}]}])))))

(defn- native-probe [entry]
  (let [address (.orElseThrow (.find (bundle/symbol-lookup entry) "__aguafria_probe"))
        handle (.downcallHandle (Linker/nativeLinker) address
                                (FunctionDescriptor/of ValueLayout/JAVA_INT
                                                       (make-array MemoryLayout 0))
                                (make-array java.lang.foreign.Linker$Option 0))]
    (.invokeWithArguments handle (ArrayList.))))

(deftest real-shared-transport-retains-native-results-and-artifact-identities
  (let [configuration (runtime/configuration)
        directory (directory)
        module "aguafria.jvm.support-owned-fixture"
        transport (slurp (io/resource "aguafria/jvm_result.zig"))
        declaration {:module module :kind :raw :name '__aguafria_jvm
                     :declaration-key [:raw '__aguafria_jvm] :code transport
                     :compiler-owned-support :stateless-jvm-transport}
        source (emitter/emit-static-dependency-module module [declaration])
        dependency {module {:module module :source source :dependencies []
                            :compiler-support (#'runtime/compiler-support-provenance
                                               module [declaration] source)}}
        collected (atom {})]
    (try
      (runtime/configure! {:cache-dir (str directory)})
      (binding [runtime/*compile-only?* true bundle/*preparing* collected
                bundle/*batch-validation?* true]
        (let [artifacts
              (mapv (fn [index]
                      (bundle/call-with-validation-scope
                       #(let [root (str "aguafria.jvm.owned-support-test-" index)
                              code (str "const transport = @import(\"" module "\")."
                                        "__aguafria_jvm;\n"
                                        "export fn __aguafria_probe() i32 { return if "
                                        "(transport.scopedCaptureSupported(i32)) " index " else -1; }")]
                          {:status :prepared
                           :artifact (#'runtime/compile-source!
                                      root code [{:module root :kind :raw :name 'fixture
                                                  :code code :public? false :jvm-adapter? true}]
                                      dependency code [module])}))) (range 3))
              candidates (mapv #(bundle/candidate (:artifact %)) artifacts)
              support (mapv #(first (filter :shared-support (:groups %))) candidates)
              pack (runtime/finish-precompile-bundles! collected {:validate-pending? true})
              entries (mapv #(bundle/find-artifact (str directory) (:artifact %)) artifacts)]
          (is (every? some? support))
          (is (= {:validated 3} (get-in pack [:validation :statuses])))
          (is (= [0 1 2] (mapv native-probe entries)))
          (is (= 1 (count (set (map :id entries)))))
          (is (= (mapv (comp #'bundle/artifact-id :artifact) artifacts)
                 (mapv :artifact entries)))
          (let [root (io/file directory "bundles" (get-in pack [:packs 0 :id]))]
            (is (= 1 (count (filter #(str/starts-with? (.getName ^java.io.File %) "support_")
                                    (.listFiles root))))))))
      (finally (runtime/configure! configuration)))))
