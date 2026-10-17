(ns aguafria.zig.resource-prepared-test
  "Mocked compiler/resource boundaries; not native acceptance receipts."
  (:require [aguafria.zig.artifact :as artifact]
            [aguafria.zig.bundle :as bundle]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.resource-artifact :as resource]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def allocator-reference
  {:category :global-const :symbol 'aguafria.std.testing/allocator})

(defn- with-directory [f]
  (let [directory (.toFile (Files/createTempDirectory "resource-prepared-" (make-array FileAttribute 0)))]
    (try (f directory)
         (finally (doseq [file (reverse (file-seq directory))] (io/delete-file file true))))))

(defn- command [directory]
  ["zig" "build-lib" "-dynamic" (str "-femit-bin=" (io/file directory "resource.dylib"))
   "/native/panic-support.dylib" "-framework" "Foundation" "-lm" "-Odebug"
   (str "-Mroot=" (io/file directory "root.zig"))])

(deftest shared-resource-recipe-and-validation
  (with-directory
    (fn [directory]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported resource compiler argument"
                           (resource/run-library-command
                            (fn [& _] (throw (ex-info "Compiler must not run" {})))
                            (conj (command directory) "--unknown-link-option") (str directory) true)))
      (doseq [[context? validation? exit expected-count]
              [[false false 0 1] [true false 0 2] [true true 0 1] [true false 1 1]]]
        (let [calls (atom [])
              argv (cond-> (command directory) validation? (assoc 3 "-fno-emit-bin"))
              result (resource/run-library-command
                      (fn [argv _] (swap! calls conj argv) {:exit exit})
                      argv (str directory) context?)]
          (is (= expected-count (count @calls)))
          (is (= exit (:exit result)))
          (if-not context?
            (is (= [argv] @calls))
            (let [frontend (first @calls)]
              (is (= ["zig" "test" "--test-no-exec"] (subvec frontend 0 3)))
              (is (some #{"--test-runner"} frontend))
              (is (some #{"-fno-emit-bin"} frontend))
              (is (= "pub fn main() void {}\n" (slurp (io/file directory "jvm_resource_runner.zig"))))
              (is (not-any? #{"/native/panic-support.dylib" "-framework" "Foundation" "-lm"} frontend))
              (when (= 2 (count @calls))
                (is (= ["/native/panic-support.dylib" "-framework" "Foundation" "-lm"]
                       (vec (take-last 4 (last @calls)))))))))))))

(deftest libc-selection-is-preserved-in-the-resource-frontend
  (with-directory
    (fn [directory]
      (doseq [flags [["-lc"] ["-l" "c"]]]
        (let [calls (atom [])
              argv (into (subvec (command directory) 0 5)
                         (concat flags (subvec (command directory) 5)))]
          (resource/run-library-command
           (fn [argv _] (swap! calls conj argv) {:exit 0}) argv (str directory) true)
          (is (some #(= flags (vec %)) (partition (count flags) 1 (first @calls))))
          (is (= "test" (second (first @calls)))))))))

(deftest resource-provenance-respects-local-precedence
  (let [context (create-ns (gensym "resource-provenance-"))
        native (with-meta 'allocator {:aguafria/zig-reference allocator-reference})
        alias (with-meta 'local {:aguafria/local? true :aguafria/jvm-initializer native})]
    (try
      (alter-meta! (intern context 'allocator nil) assoc :aguafria/zig-reference allocator-reference)
      (is (= [allocator-reference] (jvm/resource-references context '(allocator))))
      (is (= [allocator-reference] (jvm/resource-references context [alias])))
      (is (empty? (jvm/resource-references context [(with-meta 'allocator {:aguafria/local? true})])))
      (binding [emitter/*lexical-bindings* #{'allocator}]
        (is (empty? (jvm/resource-references context '(allocator)))))
      (is (empty? (jvm/resource-references context [:test 'ordinary-builtin-query])))
      (is (false? (jvm/test-resource-reference?
                   {:category :function :symbol 'aguafria.std.testing/expect})))
      (finally (remove-ns (ns-name context))))))

(deftest context-selected-before-deferred-validation
  (let [collected (atom {}) calls (atom [])]
    (with-redefs [jvm/precompile-test-resource!
                  (fn [reference]
                    (swap! calls conj [reference runtime/*native-test-context?*])
                    (bundle/observe! {:module "resource" :hash "getter" :validation-pending? true}))]
      (binding [bundle/*preparing* collected bundle/*batch-validation?* true
                runtime/*native-test-context?* false]
        (let [result (#'discovery/prepare-in-observed-context
                      [allocator-reference]
                      #(do (swap! calls conj [:adapter runtime/*native-test-context?*])
                           (bundle/observe! {:module "adapter" :hash "call" :validation-pending? true})
                           {:status :prepared}))]
          (is (= :test (:execution-context result)))
          (is (= :pending-validation (:status result)))
          (is (= 2 (count (get-in @collected [:validation-scopes (:validation-scope result) :dependencies])))))
        (is (= [[allocator-reference true] [:adapter true]] @calls))
        (is (false? runtime/*native-test-context?*))
        (is (= :runtime (:execution-context
                         (#'discovery/prepare-in-observed-context [] #(hash-map :status :prepared)))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ordinary failure"
                             (#'discovery/prepare-in-observed-context
                              [] #(throw (ex-info "ordinary failure" {:aguafria/phase :zig-compile})))))))))

(deftest preparation-and-consumption-share-lazy-resource-descriptor
  (let [registered (atom []) prepared (atom []) names (atom #{})
        existing (set (map ns-name (all-ns)))]
    (try
      (with-redefs-fn
        {#'jvm/test-resources (atom {})
         #'emitter/prepare-declaration (fn [context descriptor]
                                         (swap! names conj (ns-name context)) descriptor)
         #'runtime/register-declaration! (fn [descriptor]
                                           (is runtime/*source-only-registration?*)
                                           (swap! registered conj descriptor))
         #'jvm/precompile-constant-reader! (fn [descriptor]
                                            (is runtime/*native-test-context?*)
                                            (swap! prepared conj [:reader descriptor]))
         #'jvm/precompile-reflected-layout! (fn [module type]
                                             (is runtime/*native-test-context?*)
                                             (swap! prepared conj [:layout module type]))
         #'value/native-value (fn [descriptor realize] {:descriptor descriptor :realize realize})
         #'runtime/materialize-constant! (fn [_] (throw (ex-info "Unexpected native initialization" {})))}
        #(binding [runtime/*native-test-context?* false]
           (is (= :prepared (:status (jvm/precompile-test-resource! allocator-reference))))
           (is (= 1 (count @registered)))
           (let [handle (jvm/test-resource! allocator-reference)]
             (is (identical? handle (jvm/test-resource! allocator-reference)))
             (is (= (first @registered) (second @registered)))
             (is (= :test (get-in handle [:descriptor :execution-context]))))
           (is (= [:reader :layout] (mapv first @prepared)))
           (is (false? runtime/*native-test-context?*))))
      (finally (doseq [name @names :when (not (existing name))] (remove-ns name))))))

(defn- resource-input [directory name]
  (let [source (io/file directory (str name ".zig"))
        text (str "export fn " resource/initializer-export "() callconv(.c) void {}\n"
                  "export fn resource_getter() callconv(.c) usize { return 0; }\n")]
    (spit source text)
    {:module name :hash (artifact/key-for :resource-test [name text resource/recipe-version])
     :command ["zig" "build-lib" "-dynamic" (str "-femit-bin=" (io/file directory (str name ".dylib")))
               (str "-Mroot=" source)]
     :native-test-context? true :jvm-adapter? true :validation-pending? true}))

(defn- fake-compiler [calls fail-link?]
  (fn [argv _]
    (swap! calls conj argv)
    (if (and fail-link? (= "build-lib" (second argv)))
      {:exit 1 :err "link rejected"}
      (do (doseq [argument argv :when (str/starts-with? argument "-femit-bin=")]
            (spit (subs argument (count "-femit-bin=")) "mock library bytes"))
          {:exit 0}))))

(deftest dedicated-resource-image-publication
  (with-directory
    (fn [directory]
      (let [input (resource-input directory "owner")
            candidate (bundle/candidate input)
            collected (atom {}) calls (atom [])]
        (is (= :native-resource-image (:artifact-kind candidate)))
        (is (true? (:isolated-linkage? candidate)))
        (is (= "" (:prefix candidate)))
        (is (= #{resource/initializer-export "resource_getter"} (set (:exports candidate))))
        (is (not (:native-test-object? candidate)))
        (binding [bundle/*preparing* collected] (bundle/observe! input))
        (let [report (bundle/finish! (str directory) collected
                                     {:run-command (fake-compiler calls false)
                                      :preserve-debug! (fn [& _]) :validate-pending? true :parallelism 1})
              entry (bundle/find-artifact (str directory) input)]
          (is (= 1 (:packed-handlers report)))
          (is (= 2 (:compiler-invocations report)))
          (is (= ["test" "build-lib"] (mapv second @calls)))
          (is (= :native-resource-image (:artifact-kind entry)))
          (is (= (:exports candidate) (:exports entry)))
          (is (= "" (:prefix entry)))
          (is (.isFile (io/file (:library entry)))))))))

(deftest rejected-resource-link-publishes-no-index
  (with-directory
    (fn [directory]
      (let [a (resource-input directory "a") b (resource-input directory "b")
            collected (atom {}) calls (atom []) links (atom 0)]
        (binding [bundle/*preparing* collected] (bundle/observe! a) (bundle/observe! b))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Native resource compilation failed"
             (bundle/finish!
              (str directory) collected
              {:run-command (fn [argv cwd]
                              ((fake-compiler calls (and (= "build-lib" (second argv))
                                                        (= 2 (swap! links inc)))) argv cwd))
               :preserve-debug! (fn [& _]) :validate-pending? true :parallelism 1})))
        (is (nil? (bundle/find-artifact (str directory) a)))
        (is (nil? (bundle/find-artifact (str directory) b)))
        (is (not (.exists (io/file directory "bundles" "index"))))))))

(deftest resource-source-mutation-is-rejected-before-compiler
  (with-directory
    (fn [directory]
      (let [input (resource-input directory "mutable") candidate (bundle/candidate input)
            path (get-in candidate [:resource-sources 0 :path]) calls (atom [])]
        (spit path "// changed\n")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source changed"
                             (#'bundle/run-resource-compiler! (fake-compiler calls false)
                              candidate (:command input) directory)))
        (is (empty? @calls))))))
