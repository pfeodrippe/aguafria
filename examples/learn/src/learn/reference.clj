(ns learn.reference
  "Pinned reference inventory, structural conversion and offline HTML build."
  (:require [aguafria.zig :as az]
            [aguafria.zig.convert :as convert]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.project :as project]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [learn.inline :as inline])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.lang ProcessHandle]
           [java.net InetSocketAddress]
           [java.nio.file Files]
           [java.security MessageDigest]
           [java.util.concurrent Callable Executors ExecutionException TimeUnit]))

(def upstream-commit "24fdd5b7a4c1c8b5deb5b56756b9dbc8e08c86a8")
(def upstream-url "https://ziglang.org/documentation/0.16.0/")
(def upstream-dir "resources/upstream")

(defn write-text! [path text]
  (io/make-parents path)
  (spit path text :encoding "UTF-8"))

(defn write-edn! [path value]
  (write-text! path (with-out-str (pprint/pprint value))))

(defn read-edn [path]
  (edn/read-string (slurp path :encoding "UTF-8")))

(defn sha256-bytes [bytes]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest ^bytes bytes)
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(defn sha256 [path]
  (sha256-bytes (Files/readAllBytes (.toPath (io/file path)))))

(defn text-sha256 [text]
  (sha256-bytes (.getBytes ^String text "UTF-8")))

(defn snapshot!
  "Import the licensed reference only from the exact pinned, clean checkout.
  Refuses to replace an existing snapshot; normal builds never access the network."
  [checkout]
  (let [revision (shell/sh "git" "-C" checkout "rev-parse" "HEAD")
        changes (shell/sh "git" "-C" checkout "status" "--porcelain")]
    (when-not (and (zero? (:exit revision))
                   (= upstream-commit (str/trim (:out revision)))
                   (zero? (:exit changes))
                   (str/blank? (:out changes)))
      (throw (ex-info "Snapshot requires the clean Zig 0.16.0 checkout"
                      {:revision revision :changes changes})))
    (when (.exists (io/file upstream-dir))
      (throw (ex-info "Snapshot already exists; refusing overwrite" {})))
    (let [paths (concat ["LICENSE" "doc/langref.html.in"
                         "tools/docgen.zig" "tools/doctest.zig"]
                        (for [file (sort-by str (file-seq (io/file checkout "doc/langref")))
                              :when (.isFile file)]
                          (str "doc/langref/" (.getName file))))]
      (doseq [relative paths]
        (let [destination (io/file upstream-dir relative)]
          (io/make-parents destination)
          (io/copy (io/file checkout relative) destination)))
      (write-text! (str upstream-dir "/index.html") (slurp upstream-url))
      (write-edn! (str upstream-dir "/lock.edn")
                  {:version "0.16.0"
                   :commit upstream-commit
                   :repository "https://codeberg.org/ziglang/zig.git"
                   :html-url upstream-url
                   :files (into (sorted-map)
                                (for [path (conj (vec paths) "index.html")]
                                  [path (sha256 (io/file upstream-dir path))]))}))))

(defn verify-snapshot! []
  (let [lock (read-edn (str upstream-dir "/lock.edn"))]
    (when-not (= upstream-commit (:commit lock))
      (throw (ex-info "Reference revision differs from the supported pin" lock)))
    (doseq [[path expected] (:files lock)]
      (when-not (= expected (sha256 (io/file upstream-dir path)))
        (throw (ex-info "Reference snapshot changed" {:path path}))))
    lock))

(defn manifest
  "Keep upstream directives losslessly, including repeated options."
  [source]
  (let [start (str/last-index-of source "\n\n// ")]
    (when start
      (let [lines (mapv #(str/replace-first % #"^// " "")
                        (str/split-lines (str/trim (subs source start))))]
        {:kind (first lines)
         :options (subvec lines 1)
         :text (subs source start)}))))

(defn inventory []
  (let [lock (verify-snapshot!)
        template (slurp (io/file upstream-dir "doc/langref.html.in"))
        html (slurp (io/file upstream-dir "index.html"))
        references (mapv second (re-seq #"\{#code\|([^#]+)#\}" template))
        snippets (re-seq #"(?s)\{#(syntax|syntax_block|shell_samp)(?:\|([^#]+))?#\}(.*?)\{#end(?:syntax|_syntax_block|_shell_samp)#\}" template)]
    {:version "0.16.0"
     :sections (mapv (fn [[_ level id]] {:level level :id id})
                     (re-seq #"<h([1-6]) id=\"([^\"]+)\"" html))
     :code-references references
     :examples
     (mapv (fn [file]
             {:file (.getName file)
              :referenced? (boolean (some #{(.getName file)} references))
              :sha256 (sha256 file)
              :manifest (manifest (slurp file))})
           ;; Only the pinned sources are lessons, never local Zig cache files.
           (->> (keys (:files lock))
                (filter #(str/starts-with? % "doc/langref/"))
                sort
                (map #(io/file upstream-dir %))))
     :snippets
     (mapv (fn [index [_ kind attributes source]]
             {:id (str "snippet-" (inc index))
              :kind kind
              :attributes attributes
              :source source
              :source-sha256 (text-sha256 source)
              :status (if (= "shell_samp" kind) :zig-only :pending)
              :reason (when (= "shell_samp" kind)
                        "ZIG_ONLY: compiler/shell command, not application source.")})
           (range) snippets)}))

(defn example-id [filename]
  (-> filename
      (str/replace #"\.zig$" "")
      (str/replace #"[^a-zA-Z0-9-]+" "-")))

(defn emit-clojure
  "Evaluate the exact displayed namespace, not converter-only in-memory forms.
  Collect descriptors without starting native code or a hot-reload runtime.
  An already-open lesson is inspected in a temporary namespace, never overwritten."
  [source namespace-symbol report]
  (let [occupied? (find-ns namespace-symbol)
        evaluation-ns (if occupied?
                        (symbol (str namespace-symbol ".inspection-" (random-uuid)))
                        namespace-symbol)
        source (if occupied?
                 (str/replace-first source #"\(ns\s+[^\s()]+"
                                    (str "(ns " evaluation-ns))
                 source)
        namespace-symbol evaluation-ns
        declarations (atom [])]
    (when-let [catalog-module (:catalog-module report)]
      (project/register-catalog!
       {:schema-version 1
        :modules {(str namespace-symbol) catalog-module}}))
    (try
      (binding [*ns* *ns*
                *read-eval* false
                runtime/*registration-batch* declarations
                project/*catalog-namespace* namespace-symbol]
        (load-string source))
      (binding [*ns* (the-ns namespace-symbol)
                project/*catalog-namespace* namespace-symbol]
        ;; Batched registration bypasses the live runtime's order assignment.
        ;; Preserve authored order without exposing numeric metadata in lessons.
        (emitter/emit-module
         (str namespace-symbol)
         (map-indexed (fn [index declaration]
                        (cond-> declaration
                          (nil? (:source-order declaration))
                          (assoc :source-order index)))
                      ;; A first require may also register imported declarations
                      ;; in this batch. They belong to their own module, not to
                      ;; the lesson's root (notably builtin/os is not root.os).
                      (filter #(= (str namespace-symbol) (:module %)) @declarations))))
      (finally
        (remove-ns namespace-symbol)
        ;; `ns` marks the temporary namespace as loaded. Remove that marker too,
        ;; otherwise a later ordinary `require` skips its now-missing namespace.
        (dosync
          (alter @#'clojure.core/*loaded-libs* disj namespace-symbol))))))

(defn require-structural! [report]
  (when (some pos? (map #(get report % 0)
                       [:fallback-count :raw-declaration-count
                        :unresolved-syntax-count]))
    (throw (ex-info "Non-structural translation rejected" report))))

(defn compiler-fingerprint []
  (let [files (->> ["../../src/aguafria" "../../resources/aguafria" "src/learn"
                    "../../deps.edn" "deps.edn"]
                   (mapcat #(file-seq (io/file %)))
                   (filter #(.isFile %))
                   (sort-by str))]
    {:library (text-sha256 (pr-str (mapv #(vector (str %) (sha256 %)) files)))
     :std-catalog (with-open [input (io/input-stream (io/resource "aguafria/zig-std.edn"))]
                    (sha256-bytes (.readAllBytes input)))
     :zig (sha256 (az/zig-executable))
     :upstream (sha256 "resources/upstream/lock.edn")
     :harness (sha256 "resources/upstream/tools/doctest.zig")
     :verifier (sha256 "src/learn/reference.clj")
     :overrides (sha256 "resources/learn/overrides.edn")
     :reviews (sha256 "resources/learn/verification.edn")}))

(defn example-source-inputs
  "Hash a lesson and its transitive local source dependencies, including Zig
  imports/embedded files and the corresponding authored Clojure lessons."
  [file overrides]
  (let [upstream (io/file upstream-dir "doc/langref")
        authored (fn [name]
                   (when-let [path (:source (overrides name))]
                     (or (io/resource path)
                         (throw (ex-info "Missing authored example" {:resource path})))))
        dependencies
        (fn [source-file source]
          (if (str/ends-with? (str source-file) ".clj")
            (for [clause (drop 2 (first (inline/read-forms source)))
                  :when (and (seq? clause) (= :require (first clause)))
                  spec (rest clause)
                  :let [namespace (if (vector? spec) (first spec) spec)]
                  :when (and (symbol? namespace)
                             (str/starts-with? (str namespace) "learn."))]
              (let [resource (str (-> (str namespace)
                                     (str/replace "." "/")
                                     (str/replace "-" "_")) ".clj")]
                (or (io/resource resource)
                    (throw (ex-info "Missing lesson dependency" {:resource resource})))))
            (mapcat
             (fn [[_ name]]
               (let [dependency (io/file (.getParentFile source-file) name)]
                 (cond-> [dependency]
                   (authored (.getName dependency))
                   (conj (authored (.getName dependency))))))
             (re-seq #"@(?:import|embedFile)\(\"([^\"]+\.[^\"]+)\"\)" source))))]
    (loop [pending (cond-> [(io/file upstream file)] (authored file) (conj (authored file)))
           inputs (sorted-map)]
      (if-let [path (first pending)]
        (let [source-file (.getCanonicalFile (io/file path))
              name (str source-file)]
          (if (contains? inputs name)
            (recur (next pending) inputs)
            (if (.isFile source-file)
              (recur (concat (next pending)
                             (dependencies source-file (slurp source-file)))
                     (assoc inputs name (sha256 source-file)))
              (recur (next pending) (assoc inputs name :missing)))))
        inputs))))

(defn translation-artifacts
  [{:keys [file clojure-path status]}]
  (into (sorted-map)
        (map (fn [path]
               [path (when (.isFile (io/file path)) (sha256 path))]))
        (cond-> []
          clojure-path (conj clojure-path)
          (= :translated status) (conj (str "build/emitted/" file)))))

(defn reusable-example?
  [cached inputs translation accepted-statuses]
  (let [artifacts (translation-artifacts translation)]
    (and (contains? accepted-statuses (:status cached))
         (= inputs (:inputs cached))
         (every? some? (vals artifacts))
         (= artifacts (:artifacts cached)))))

(defn translate-one! [{:keys [file sha256] :as example}]
  (let [id (example-id file)
        override (get (read-edn "resources/learn/overrides.edn") file)
        output (str "build/translations/" id ".clj")
        ;; Converter catalogs carry different defaults from handwritten code.
        ;; Never register them under the lesson's namespace.
        options {:namespace (symbol (str "learn.reference.draft." id))
                 :cache-dir ".aguafria/zig"
                 :source-display-path file}]
    (try
      (cond
        (= :zig-only (:status override))
        {:file file :id id :source-sha256 sha256
         :status :zig-only :reason (:reason override)}

        (:expected-front-end-error override)
        (let [source (slurp (io/resource (:source override)))
              namespace-symbol (second (read-string source))
              failure (try
                        (emit-clojure source namespace-symbol {})
                        nil
                        (catch Exception error error))]
          (when-not (and failure
                         (some #(str/includes? (or (ex-message %) "")
                                               (:expected-front-end-error override))
                               (take-while some? (iterate ex-cause failure))))
            (throw (ex-info "Expected Aguafria binding diagnostic was not produced"
                            {:file file :error (some-> failure .getMessage)})))
          (write-text! output source)
          {:file file :id id :source-sha256 sha256
           :status :translated-front-end-error
           :authored-source (:source override)
           :clojure-path output
           :diagnostic (.getMessage failure)
           :reason (:reason override)})

        :else
        (let [converted (when-not (:source override)
                          (convert/convert-file
                           (str upstream-dir "/doc/langref/" file) options))
              report (:report converted)
              source (if-let [resource (:source override)]
                       (slurp (io/resource resource))
                       (:clojure-source converted))
              namespace-symbol (if override
                                 (second (read-string source))
                                 (:namespace converted))]
          (require-structural! report)
          (write-text! output source)
          (write-text! (str "build/emitted/" file)
                       (emit-clojure source namespace-symbol
                                     (if override {} report)))
          {:file file :id id :source-sha256 sha256
           :status :translated :verification :pending
           :authored-source (:source override)
           :clojure-path output
           :report report}))
      (catch Exception failure
        (let [cause (last (take-while some? (iterate ex-cause failure)))
              kind (get-in example [:manifest :kind])
              expected-compile-error? (or (str/starts-with? (or kind "") "test_error=")
                                          (= kind "exe=build_fail"))]
          (if (and expected-compile-error?
                   (= :compile-syntax-check (:clojure.error/phase (ex-data cause)))
                   (.isFile (io/file output)))
            {:file file :id id :source-sha256 sha256
             :status :translated-front-end-error
             :authored-source (:source override) :clojure-path output
             :diagnostic (.getMessage failure)
             :reason (.getMessage cause)}
            {:file file :id id :source-sha256 sha256
             :status :compiler-gap
             :error (.getMessage failure)
             :details (ex-data failure)}))))))

(defn translate!
  ([] (translate! (:examples (inventory))))
  ([examples]
   (let [fingerprint (compiler-fingerprint)
         overrides (read-edn "resources/learn/overrides.edn")
         previous (when (.isFile (io/file "build/translations.edn"))
                    (into {} (map (juxt :file identity)) (read-edn "build/translations.edn")))
         results (mapv (fn [{:keys [file] :as example}]
                         (let [inputs {:compiler fingerprint
                                       :sources (example-source-inputs file overrides)}
                               cached (get previous file)
                               reuse? (reusable-example? cached inputs cached
                                                         #{:translated :zig-only :translated-front-end-error})
                               result (if reuse?
                                        cached
                                        (let [translated (translate-one! example)]
                                          (assoc translated :inputs inputs
                                                 :artifacts (translation-artifacts translated))))]
                           (println (if reuse? "cached translation" (name (:status result))) file)
                           (flush)
                           result))
                       examples)]
     (write-edn! "build/translations.edn" results)
     (frequencies (map :status results)))))

(def context-reasons
  {"c" "ZIG_ONLY: C interoperability example. Keep the C caller/header unchanged; the paired Zig implementation has its own Aguafria tab."
   "javascript" "ZIG_ONLY: JavaScript WebAssembly host. Keep this host program unchanged when calling the Aguafria-generated module."
   "peg" "ZIG_ONLY: the formal grammar of Zig source, not an application program. Aguafria uses Clojure's reader syntax."})

(declare verify-native-pair! verify-discovery!)

(defn fragment-fingerprint []
  (assoc (compiler-fingerprint)
         :fragment-overrides (sha256 "resources/learn/fragment-overrides.edn")
         :fragment-fixtures (sha256 "resources/learn/fragment-fixtures.edn")
         :discovery-fixture (sha256 "resources/learn/discovery-fixture.edn")))

(defn fragment-test-source [source entry {:keys [zig-prefix test-body]}]
  (str zig-prefix "\n" source "\nconst subject = " entry ";\n"
       "test \"fragment behavior\" {\n" test-body "\n}\n"))

(defn emitted-entry [clojure-source]
  (let [definition (first (filter #(contains? #{'az/defn 'az/defn-} (first %))
                                  (inline/read-forms clojure-source)))]
    (when-not definition
      (throw (ex-info "A function fixture needs an authored entry point" {})))
    (emitter/identifier (second definition))))

(defn emit-fragment
  "Render incomplete source as syntax data. Never evaluate its declarations or
  manufacture missing Vars; native comparisons supply their own explicit context."
  [source]
  (let [[ns-form & forms] (inline/read-forms source)
        namespace-symbol (symbol (str "learn.fragment.inspection-" (random-uuid)))
        imports (atom [])]
    (try
      (binding [*ns* *ns* runtime/*registration-batch* imports]
        (eval (with-meta (list* 'ns namespace-symbol (drop 2 ns-form)) (meta ns-form)))
        (let [context *ns*
              declarations
              (mapv
               (fn [form]
                 (if (= 'az/defimport (first form))
                   (do (eval form) (last @imports))
                   (-> (emitter/container-description
                        context
                        (list 'container {:kind :struct}
                              [(emitter/qualify-form
                                context (convert/nested-declaration-form form))]))
                       :members first)))
               (remove #(= 'comment (first %)) forms))]
          (emitter/emit-module (str namespace-symbol)
                               (map-indexed #(assoc %2 :source-order %1) declarations))))
      (finally
        (remove-ns namespace-symbol)
        (dosync (alter @#'clojure.core/*loaded-libs* disj namespace-symbol))))))

(defn translate-block!
  "Round-trip an upstream explanatory block without inventing missing context.
  Parsing emitted Zig is syntax verification, not an execution claim."
  [{:keys [id attributes source source-sha256]}]
  (let [[language title] (str/split attributes #"\|" 2)
        base {:id id :file title :source-sha256 source-sha256}]
    (if-let [reason (get context-reasons language)]
      (assoc base :status :zig-only :reason reason)
      (try
        (when-not (= "zig" language)
          (throw (ex-info "Unreviewed snippet language" {:language language})))
        (let [input (str "build/snippets/" id ".zig")
              output (str "build/snippets/" id ".clj")
              emitted-path (str "build/snippets/" id ".emitted.zig")
              override (get (read-edn "resources/learn/fragment-overrides.edn") id)
              fixture (get (read-edn "resources/learn/fragment-fixtures.edn") id)
              options {:namespace (symbol (str "learn.reference.draft." id))
                       :cache-dir ".aguafria/zig"
                       :source-display-path title}]
          (write-text! input source)
          (let [converted (convert/convert-file input options)
                report (:report converted)
                clojure-source (if-let [resource (:source override)]
                                 (slurp (io/resource resource))
                                 (:clojure-source converted))
                namespace-symbol (if override
                                   (second (read-string clojure-source))
                                   (:namespace converted))]
            (require-structural! report)
            (write-text! output clojure-source)
            (let [emitted (emit-fragment clojure-source)]
              (convert/parse-source emitted (assoc options :path title))
              (write-text! emitted-path emitted))
            (let [comparison
                  (cond
                    fixture
                    (verify-native-pair!
                     (str "build/fragment-outcomes/" id)
                     (fragment-test-source source (:entry fixture) fixture)
                     (fragment-test-source (slurp emitted-path)
                                           (emitted-entry clojure-source) fixture))
                    (= "snippet-1181" id)
                    (verify-discovery! source (slurp emitted-path)))]
              (when (and comparison
                         (not (contains? #{:output-matched :discovery-matched}
                                         (:status comparison))))
                (throw (ex-info "Fragment behavior differs from original Zig"
                                {:comparison comparison})))
              (assoc base
                   :status :translated
                   :verification (or (:status comparison) :syntax-roundtrip-passed)
                   :verification-scope (cond
                                         fixture :shared-context-fixture
                                         comparison :shared-module-discovery
                                         :else :explanatory-block)
                   :comparison comparison
                   :fingerprint (fragment-fingerprint)
                   :authored-source (:source override)
                   :context-review (:context-only override)
                   :note "Explanatory fragment: displayed syntax rendered without evaluating declarations; emitted Zig parsed. Surrounding definitions/imports may be omitted by the reference; this is not a standalone execution test."
                   :clojure-path output
                   :clojure-sha256 (sha256 output)
                   :emitted-path emitted-path
                   :emitted-sha256 (sha256 emitted-path)
                   :report report))))
        (catch Exception failure
          (assoc base :status :compiler-gap
                 :error (.getMessage failure) :details (ex-data failure)))))))

(declare capture-comment-repl!)

(defn translate-blocks! []
  (let [blocks (filter #(= "syntax_block" (:kind %)) (:snippets (inventory)))
        fingerprint (fragment-fingerprint)
        previous (when (.isFile (io/file "build/blocks.edn"))
                   (into {} (map (juxt :id identity)) (read-edn "build/blocks.edn")))
        results (mapv (fn [block]
                        (let [cached (get previous (:id block))
                              current? (and (= (:source-sha256 block) (:source-sha256 cached))
                                            (or (= :zig-only (:status cached))
                                                (and (= fingerprint (:fingerprint cached))
                                                     (= :translated (:status cached))
                                                     (every? (fn [[path checksum]]
                                                               (and path (.isFile (io/file path))
                                                                    (= checksum (sha256 path))))
                                                             [[(:clojure-path cached) (:clojure-sha256 cached)]
                                                              [(:emitted-path cached) (:emitted-sha256 cached)]])
                                                     (= (slurp (io/resource (:authored-source cached)))
                                                        (slurp (:clojure-path cached))))))]
                          (if current?
                            cached
                            (let [result (translate-block! block)]
                              (if (= :translated (:status result))
                                (assoc result :repl-transcript
                                       (capture-comment-repl! (slurp (:clojure-path result)) nil))
                                result)))))
                      blocks)]
    (write-edn! "build/blocks.edn" results)
    (frequencies (map :status results))))

(defn run-command
  "Bound a compiler/example process and drain both pipes while it runs."
  [arguments timeout-seconds]
  (let [process (.start (ProcessBuilder. ^java.util.List (vec arguments)))
        stdout (future (slurp (.getInputStream process)))
        stderr (future (slurp (.getErrorStream process)))
        finished? (.waitFor process timeout-seconds TimeUnit/SECONDS)]
    (when-not finished?
      (with-open [children (.descendants (.toHandle process))]
        (.forEach children
                  (reify java.util.function.Consumer
                    (accept [_ child] (.destroyForcibly ^ProcessHandle child)))))
      (.destroyForcibly process)
      (.waitFor process))
    {:command arguments
     :exit (.exitValue process)
     :timed-out? (not finished?)
     :out @stdout
     :err @stderr}))

(declare compare-observations)

(defn verify-native-pair!
  "Run equivalent test contexts with the same filename and embedded compiler.
  Compare both output streams exactly; a pair of failures is not a passing test."
  [directory original emitted]
  (let [runs (mapv (fn [language source]
                     (let [path (str directory "/" language "/subject.zig")]
                       (write-text! path source)
                       (run-command [(az/zig-executable) "test" path] 60)))
                   ["zig" "aguafria"] [original emitted])
        [left right] runs
        streams (mapv #(compare-observations "test" (% left) (% right)) [:out :err])
        result {:status (cond
                          (some #(or (:timed-out? %) (not (zero? (:exit %)))) runs)
                          :native-test-failed
                          (every? #(= :output-matched (:status %)) streams) :output-matched
                          :else :observation-mismatch)
                :original left :aguafria right :streams streams}]
    (write-edn! (str directory "/comparison.edn") result)
    result))

(declare failure-messages)

(defn- discovery-runner-observation
  "Validate runner framing before separating the known discovery-only test.
  Payload bytes are retained; unknown labels, extra output and counts fail closed."
  [output expected-count expected-named unnamed-label]
  (let [summary (str "All " expected-count " tests passed.\n")
        summary? (str/ends-with? output summary)
        body (if summary? (subs output 0 (- (count output) (count summary))) output)
        matcher (re-matcher #"(?m)^(\d+)/(\d+) ([^\r\n]+?)\.\.\." body)
        headers (loop [result []]
                  (if (.find matcher)
                    (recur (conj result {:index (Long/parseLong (.group matcher 1))
                                         :total (Long/parseLong (.group matcher 2))
                                         :label (.group matcher 3)
                                         :start (.start matcher) :end (.end matcher)}))
                    result))
        tests (mapv (fn [index header]
                      (let [end (if (< (inc index) (count headers))
                                  (:start (nth headers (inc index))) (count body))
                            segment (subs body (:end header) end)
                            passed? (str/ends-with? segment "OK\n")]
                        (assoc header :passed? passed?
                               :payload (if passed?
                                          (subs segment 0 (- (count segment) 3))
                                          segment))))
                    (range (count headers)) headers)
        unnamed (filterv #(= unnamed-label (:label %)) tests)
        named (filterv #(not= unnamed-label (:label %)) tests)
        valid? (and summary?
                    (= expected-count (count tests))
                    (if (seq tests) (zero? (:start (first tests))) (empty? body))
                    (= (vec (range 1 (inc expected-count))) (mapv :index tests))
                    (every? #(= expected-count (:total %)) tests)
                    (every? :passed? tests)
                    (= (vec expected-named) (mapv :label named))
                    (= (if unnamed-label 1 0) (count unnamed))
                    (every? #(empty? (:payload %)) unnamed))]
    {:valid? (boolean valid?) :summary-valid? summary?
     :tests tests :named-tests (mapv :label named)
     :payload (apply str (map :payload named))}))

(defn- discovery-run!
  [directory side source fixture scenario zig]
  (try
    (let [side-directory (io/file directory (name (:id scenario)) (name side))
          root (.getAbsolutePath (io/file side-directory (:root-file fixture)))
          arguments (into [zig]
                          (map #(if (= % (:root-file fixture)) root %))
                          (:args scenario))]
      ;; All modules are rewritten from the same base and scenario append. Each
      ;; invocation has its own directory; previous failure probes cannot leak.
      (doseq [[path contents] (:modules fixture)]
        (write-text! (io/file side-directory path)
                     (str contents (get (:module-appends scenario) path ""))))
      (write-text! root source)
      (assoc (run-command arguments (get-in fixture [:execution-contract :timeout-seconds]))
             :source root))
    (catch Exception failure
      {:exit nil :out "" :err "" :execution-error (.getMessage failure)
       :exception-class (.getName (class failure)) :details (ex-data failure)})))

(defn- discovery-compare-scenario
  [scenario original emitted unnamed-label]
  (let [runs [original emitted]
        expect (:expect scenario)
        executed? (every? #(and (integer? (:exit %))
                               (not (:timed-out? %))
                               (not (:execution-error %))) runs)
        success? (and executed? (every? #(zero? (:exit %)) runs))
        comparison
        (cond
          (= :nonzero (:exit expect))
          (let [diagnostics (mapv #(failure-messages (:err %)) runs)
                stdout-errors (mapv #(failure-messages (:out %)) runs)]
            {:matched? (and executed?
                            (every? #(not (zero? (:exit %))) runs)
                            (every? #(= [(:diagnostic expect)] %) diagnostics)
                            (every? empty? stdout-errors)
                            (= (:out original) (:out emitted)))
             :kind :expected-comptime-failure
             :diagnostics diagnostics :stdout-errors stdout-errors})

          (= :native-test (:mode scenario))
          (let [observations
                (mapv (fn [side run]
                        (discovery-runner-observation
                         (:err run) (get-in expect [:test-counts side])
                         (:named-tests expect) (when (= side :original) unnamed-label)))
                      [:original :emitted] runs)]
            {:matched? (and success? (every? :valid? observations)
                            (= (:out original) (:out emitted))
                            (apply = (map :payload observations)))
             :kind :imported-test-behavior
             :whole-runner-identical? false
             :observations observations})

          :else
          {:matched? (and success?
                          (= (:out original) (:out emitted))
                          (= (:err original) (:err emitted)))
           :kind :compile-only})]
    {:id (:id scenario) :mode (:mode scenario) :expect expect
     :status (if (:matched? comparison) :discovery-matched :discovery-mismatch)
     :comparison comparison :original original :emitted emitted}))

(defn verify-discovery!
  "Compare snippet-1181's import discovery, not its intentionally different
  empty-test framing. Keep every process result, including compilation errors."
  [original emitted]
  (let [fixture-path "resources/learn/discovery-fixture.edn"
        base {:fingerprint (assoc (compiler-fingerprint)
                                  :discovery-fixture (sha256 fixture-path))
              :source-sha256 {:original (text-sha256 original)
                              :emitted (text-sha256 emitted)}}
        result
        (try
          (let [fixture (read-edn fixture-path)
                modules (:modules fixture)
                scenarios (:scenarios fixture)
                _ (when-not (and (= "subject.zig" (:root-file fixture))
                                 (= #{"api.zig" "windows_api.zig" "tests.zig" "windows_tests.zig"}
                                    (set (keys modules)))
                                 (every? string? (vals modules))
                                 (= 13 (count scenarios))
                                 (= 13 (count (set (map :id scenarios)))))
                    (throw (ex-info "Unexpected discovery fixture structure" {})))
                _ (doseq [{:keys [id args mode module-appends]} scenarios]
                    (when-not (and (keyword? id)
                                   (re-matches #"[a-z0-9-]+" (name id))
                                   (vector? args) (every? string? args)
                                   (= 1 (count (filter #{"subject.zig"} args)))
                                   (every? #(contains? modules %) (keys module-appends))
                                   (every? string? (vals module-appends))
                                   (or (not= :cross-compile-only mode)
                                       (and (some #{"-fno-emit-bin"} args)
                                            (some #{"x86_64-windows-gnu"} args))))
                      (throw (ex-info "Invalid discovery scenario" {:scenario id}))))
                _ (when-not (re-find #"(?i)mac|darwin" (System/getProperty "os.name"))
                    (throw (ex-info "Discovery native scenarios require a macOS host"
                                    {:os (System/getProperty "os.name")})))
                declarations
                (mapv #(convert/test-labels
                        (convert/parse-source % {:path "subject.zig" :cache-dir ".aguafria/zig"}))
                      [original emitted])
                _ (when-not (and (= 1 (count (first declarations)))
                                 (= :unnamed (:kind (ffirst declarations)))
                                 (empty? (second declarations)))
                    (throw (ex-info "Discovery root test declarations changed"
                                    {:declarations declarations})))
                unnamed-label (str "subject." (:label (ffirst declarations)))
                directory (io/file "build/discovery-outcomes" (str (java.util.UUID/randomUUID)))
                zig (az/zig-executable)
                outcomes
                (mapv (fn [scenario]
                        (let [left (discovery-run! directory :original original fixture scenario zig)
                              right (discovery-run! directory :emitted emitted fixture scenario zig)]
                          (discovery-compare-scenario scenario left right unnamed-label)))
                      scenarios)]
            (assoc base
                   :status (if (every? #(= :discovery-matched (:status %)) outcomes)
                             :discovery-matched :discovery-mismatch)
                   :scope :shared-module-discovery
                   :whole-runner-identical? false
                   :root-test-declarations declarations
                   :directory (.getAbsolutePath directory)
                   :scenarios outcomes))
          (catch Exception failure
            (assoc base :status :discovery-mismatch :scenarios []
                   :error (.getMessage failure)
                   :exception-class (.getName (class failure))
                   :details (ex-data failure))))]
    (write-edn! "build/discovery-outcomes.edn" result)
    result))

(defn inline-fingerprint []
  (assoc (compiler-fingerprint)
         :inline-verifier (sha256 "src/learn/inline.clj")
         :inline-sources (into (sorted-map)
                               (for [resource inline/mapping-resources]
                                 [resource (sha256 (io/file "resources" resource))]))))

(defn verify-inlines! []
  (let [snippets (inline/translate (filterv #(= "syntax" (:kind %))
                                           (:snippets (inventory))))
        authored (vec (sort-by :id
                              (vals (into {} (for [snippet snippets
                                                   :when (= :translated (:status snippet))]
                                               [(:source snippet) snippet])))))
        executable (filterv :standalone? authored)
        fingerprint (inline-fingerprint)
        checked (mapv #(select-keys % [:id :source :source-sha256
                                      :clojure-source :zig-source :standalone?]) authored)
        previous (when (.isFile (io/file "build/inline-outcomes.edn"))
                   (read-edn "build/inline-outcomes.edn"))
        cached? (and (= fingerprint (:fingerprint previous))
                     (= checked (:snippets previous))
                     (= :output-matched (get-in previous [:comparison :status])))
        syntax (inline/syntax-unit authored)
        comparison (if cached?
                     (:comparison previous)
                     (do
                       (convert/parse-source syntax {:path "inline-syntax.zig" :cache-dir ".aguafria/zig"})
                       (write-text! "build/inline-outcomes/syntax.zig" syntax)
                       (verify-native-pair! "build/inline-outcomes"
                                            (inline/execution-unit executable :zig)
                                            (inline/execution-unit executable :aguafria))))
        result {:fingerprint fingerprint
                :comparison comparison
                :snippets checked}]
    (when-not cached? (write-edn! "build/inline-outcomes.edn" result))
    (when-not (= :output-matched (:status comparison))
      (throw (ex-info "Inline expression comparison failed" result)))
    {:authored-checked (count authored)
     :syntax-checked (count (filter :zig-source authored))
     :output-matched (count executable)}))

(defn current-inlines
  ([snippets]
   (let [path "build/inline-outcomes.edn"]
     (current-inlines snippets (when (.isFile (io/file path)) (read-edn path)))))
  ([snippets report]
   (let [current? (and (= (inline-fingerprint) (:fingerprint report))
                      (= :output-matched (get-in report [:comparison :status])))
        checked (when current? (into {} (map (juxt :source identity)) (:snippets report)))]
    (mapv (fn [{:keys [source] :as snippet}]
            (let [evidence (get checked source)
                  fields [:source :source-sha256 :clojure-source :zig-source :standalone?]]
              (if (and evidence (= (select-keys snippet fields) (select-keys evidence fields)))
                (assoc snippet
                       :verification (cond
                                       (:standalone? snippet) :output-matched
                                       (= :syntax-note (:form-kind snippet)) :reviewed-syntax-note
                                       (= :reference (:form-kind snippet)) :catalog-var-matched
                                       :else :syntax-emission-passed))
                snippet)))
          snippets))))

(defn doctest-tool! []
  (let [binary (.getAbsolutePath (io/file "build/bin/doctest"))
        source (.getAbsolutePath (io/file upstream-dir "tools/doctest.zig"))
        zig (az/zig-executable)]
    (when-not (.isFile (io/file binary))
      (io/make-parents binary)
      (let [result (run-command [zig "build-exe" source "-OReleaseSafe"
                                 (str "-femit-bin=" binary)] 120)]
        (when-not (zero? (:exit result))
          (throw (ex-info "Could not build pinned upstream doctest tool" result)))))
    {:tool binary :zig zig}))

(defn run-doctest! [{:keys [tool zig]} file variant]
  (let [source (.getAbsolutePath
                (io/file (if (= variant :zig)
                           (str upstream-dir "/doc/langref")
                           "build/verification-input") file))
        output (.getAbsolutePath
                (io/file "build/doctest" (name variant) (str file ".html")))
        cache (.getAbsolutePath (io/file "build/doctest-cache"))]
    (io/make-parents output)
    (run-command [tool "--zig" zig "--cache-root" cache
                  "-i" source "-o" output] 90)))

(defn decode-html [text]
  (str/replace
   text #"&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);"
   (fn [[_ entity]]
     (or (get {"amp" "&" "lt" "<" "gt" ">" "quot" "\"" "apos" "'"} entity)
         (String. (Character/toChars
                   (if (str/starts-with? entity "#x")
                     (Integer/parseInt (subs entity 2) 16)
                     (Integer/parseInt (subs entity 1)))))))))

(defn observed-output
  "Read actual doctest output, excluding rendered source and shell commands.
  Do not erase numbers, addresses or whitespace from program output."
  [html]
  (->> (re-seq #"(?s)<samp>(.*?)</samp>" html)
       (map (fn [[_ sample]]
              (-> sample
                  (str/replace #"(?m)^\$ <kbd>[^\n]*</kbd>\n?" "")
                  (str/replace #"<[^>]*>" "")
                  decode-html)))
       (apply str)))

(defn execution-output [html]
  ;; Verbose compiler traces are not stdout/stderr of the executable. The
  ;; upstream harness puts each invocation in a <kbd> command boundary.
  (some-> (re-find #"(?s)\$ <kbd>\./[^\n]*</kbd>\n(.*?)</samp>" html)
          second
          (str/replace #"<[^>]*>" "")
          decode-html))

(defn failure-messages
  "Compare diagnostic messages, not unstable stack addresses/source locations.
  Keep every semantic error/panic; drop only the test runner's command summary."
  [output]
  (->> (str/split-lines output)
       (keep (fn [line]
               ;; Match diagnostic headers, not echoed source like
               ;; `const casted_error: Set2 = ...` in a panic's stack trace.
               (or (second (re-matches #"(?:\S.*:\d+:\d+: )?error: (.+)" line))
                   (second (re-matches #"(?:\d+/\d+ .*?\.\.\.)?(?:thread \d+ )?(?:panic: |Panic! |FAIL \()(.+)" line))
                   (re-matches #"\d+ tests leaked memory\." line))))
       (remove #(str/starts-with? % "the following test command"))
       vec))

(defn compile-log-output [output]
  ;; Values printed by @compileLog are program observations, not stack locations.
  ;; Compare the payload byte-for-byte, including numbers, types and whitespace.
  (some-> (re-find #"(?ms)^Compile Log Output:\n(.*)\z" output) second))

(defn compare-observations
  [kind original converted]
  (let [failure? (or (str/starts-with? kind "test_error=")
                     (str/starts-with? kind "test_safety=")
                     (str/starts-with? kind "obj=")
                     (contains? #{"exe=fail" "exe=build_fail"} kind))
        compile-only? (contains? #{"syntax" "obj" "lib"} kind)
        diagnostic #(hash-map :messages (failure-messages %)
                              :compile-log (compile-log-output %))
        left (if failure? (diagnostic original) original)
        right (if failure? (diagnostic converted) converted)
        enough? (or (not failure?) (seq (:messages left)))]
    {:status (cond
               (not enough?) :diagnostic-review-required
               (not= left right) :observation-mismatch
               compile-only? :compile-only-matched
               failure? :diagnostics-matched
               :else :output-matched)
     :original left
     :aguafria right}))

(defn normalize-test-runner-labels
  "Match named test Vars to source-order identities for comparison only.
  Replace only exact, AST-derived labels at test-runner line starts. Counts,
  order, statuses and all program output remain intact; displayed REPL output
  is never rewritten. Unknown labels remain unchanged and fail comparison."
  [module tests output]
  (reduce-kv
   (fn [text index {:keys [label]}]
     (let [prefix (str module "." label "...")
           pattern (re-pattern (str "(?m)^(\\d+/\\d+ )"
                                    (java.util.regex.Pattern/quote prefix)))]
       (str/replace text pattern
                    (fn [[_ progress]]
                      (str progress module ".<test-" index ">...")))))
   output (vec tests)))

(defn compare-test-observations [file outputs]
  (let [module (str/replace file #"\.zig$" "")
        declarations (mapv #(convert/test-labels (convert/parse-file %))
                           [(io/file upstream-dir "doc/langref" file)
                            (io/file "build/emitted" file)])
        normalized (mapv #(normalize-test-runner-labels module %1 %2)
                         declarations outputs)]
    (assoc (if (= (count (first declarations)) (count (second declarations)))
             (apply compare-observations "test" normalized)
             {:status :test-declaration-count-mismatch})
           :normalization :source-test-labels
           :test-declarations declarations
           :original (first outputs)
           :aguafria (second outputs))))

(defn compare-doctest-output [file {:keys [kind options]}]
  (let [paths (map #(io/file "build/doctest" % (str file ".html"))
                   ["zig" "aguafria"])
        review (get (read-edn "resources/learn/verification.edn") file)]
    (if (every? #(.isFile %) paths)
      (let [html (mapv slurp paths)
            executable? (contains? #{"exe=succeed" "exe=fail"} kind)
            outputs (mapv (if executable? execution-output observed-output) html)
            allocation-address? (= :allocation-address (:normalization review))]
        (cond
          (and (every? nil? outputs)
               executable?
               (some #(str/starts-with? % "target=") options))
          {:status :compile-only-matched :scope :cross-target-not-executed}

          (some nil? outputs)
          {:status :missing-execution-observation}

          allocation-address?
          (let [valid? (every? #(when-let [[_ address] (re-matches #"ptr=i32@([0-9a-fA-F]+)\n" %)]
                                 (pos? (.signum (java.math.BigInteger. address 16))))
                               outputs)]
            {:status (if valid? :output-matched :observation-mismatch)
             :normalization (:normalization review)
             :reason (:reason review)
             :original (first outputs) :aguafria (second outputs)})

          (= "test" kind)
          (compare-test-observations file outputs)

          :else
          (apply compare-observations kind outputs)))
      {:status :missing-observation})))

(def ^:dynamic *example-context* nil)

(defonce ^:private repl-capture-lock (Object.))

(defn parallel-builds
  "Bound independent compiler processes using virtual JVM worker threads.
  Preserve input order, dynamic bindings and the original failure cause."
  [jobs f inputs]
  (when-not (and (integer? jobs) (<= 1 jobs 32))
    (throw (ex-info "Build jobs must be an integer between 1 and 32" {:jobs jobs})))
  (if (= 1 jobs)
    (mapv f inputs)
    (with-open [executor (Executors/newFixedThreadPool jobs (.factory (Thread/ofVirtual)))]
      (let [tasks (mapv (fn [input]
                          (.submit executor ^Callable (bound-fn [] (f input)))) inputs)]
        (try
          (mapv #(.get ^java.util.concurrent.Future %) tasks)
          (catch ExecutionException error
            (doseq [task tasks] (.cancel ^java.util.concurrent.Future task true))
            (throw (.getCause error))))))))

(defn prepare-example-context!
  "Refresh changed translations and prepare native fixture inputs. Unchanged
  namespaces are not reevaluated merely to check the verification cache."
  []
  (translate!)
  (let [catalog (inventory)
        translations (into {} (map (juxt :file identity))
                           (read-edn "build/translations.edn"))]
    (doseq [{:keys [file manifest]} (:examples catalog)
            :let [translation (translations file)]
            :when (= :translated (:status translation))]
      (let [emitted (slurp (str "build/emitted/" file))]
        (write-text! (str "build/verification-input/" file)
                     (str emitted (:text manifest)))))
    {:tool (assoc (doctest-tool!) :fingerprint (compiler-fingerprint))
     :examples (into {} (map (juxt :file identity)) (:examples catalog))
     :translations translations}))

(defn example-scope [{:keys [kind options]}]
  (cond
    (some #(str/starts-with? % "target=") options) :cross-target
    (= "syntax" kind) :syntax-only
    (contains? #{"obj" "lib"} kind) :compile-only
    (or (str/starts-with? kind "test_error=")
        (str/starts-with? kind "test_safety=")
        (str/starts-with? kind "obj=")
        (contains? #{"exe=fail" "exe=build_fail"} kind)) :expected-failure
    :else :host))

(defn run-example!
  "Run a reference example from a normal Clojure REPL, using embedded Zig.
  Print the native result, then return a small execution summary. Upstream test
  directives still determine whether this runs, compiles or intentionally fails.
  Requires the generated translations (:translate); does not invoke PATH Zig."
  [file]
  (if-not *example-context*
    (binding [*example-context* (prepare-example-context!)]
      (run-example! file))
    (let [{:keys [tool examples translations]} *example-context*
          example (get examples file)
          translation (get translations file)]
      (when-not (and example translation)
        (throw (ex-info "Unknown reference example" {:file file})))
      (when (= :zig-only (:status translation))
        (throw (ex-info (:reason translation) {:file file :status :zig-only})))
      ;; Reevaluating the displayed source is part of this actual REPL call,
      ;; including intentional front-end errors. No copied Zig-side transcript.
      (let [source (slurp (:clojure-path translation))
            override (get (read-edn "resources/learn/overrides.edn") file)
            emitted (emit-clojure source (second (read-string source))
                                  (if (:source override) {} (:report translation)))
            manifest (:manifest example)]
        (write-text! (str "build/emitted/" file) emitted)
        (write-text! (str "build/verification-input/" file)
                     (str emitted (:text manifest)))
        (let [result (run-doctest! tool file :aguafria)
              passed? (zero? (:exit result))
              html-path (io/file "build/doctest/aguafria" (str file ".html"))
              html (when (and passed? (.isFile html-path)) (slurp html-path))
              executable? (contains? #{"exe=succeed" "exe=fail"} (:kind manifest))
              output (when html
                       (if executable? (execution-output html) (observed-output html)))]
          (when output (print output))
          (when-not passed?
            (print (:out result))
            (binding [*out* *err*] (print (:err result))))
          {:file file
           :status (if passed? :checked :failed)
           :scope (example-scope manifest)
           :exit (:exit result)
           :timed-out? (:timed-out? result)})))))

(defn capture-example-repl!
  "Evaluate the displayed REPL form and capture its actual output and value."
  [file context]
  (let [form (pr-str (list 'run-example! file))
        stdout (java.io.StringWriter.)
        stderr (java.io.StringWriter.)
        result (binding [*example-context* context
                         *ns* (the-ns 'learn.reference)
                         *out* stdout
                         *err* stderr]
                 (try
                   {:value (eval (read-string form))}
                   (catch Exception error
                     {:exception {:class (.getName (class error))
                                  :message (.getMessage error)}})))]
    (merge {:namespace "learn.reference" :form form
            :stdout (str stdout) :stderr (str stderr)} result)))

(defn comment-calls
  "The authored REPL recipe is the final comment form, never inferred at display time."
  [source]
  (let [form (last (inline/read-forms source))]
    (when (some #{'reference/run-example! 'learn.reference/run-example!
                  'reference/check-snippet! 'learn.reference/check-snippet!}
                (tree-seq coll? seq form))
      (throw (ex-info "A REPL recipe must call its own Vars, not a file runner" {})))
    (if (= 'comment (first form)) (vec (rest form)) [])))

(defn- capture-repl-evaluation!
  [namespace-symbol form invoke]
  (let [stdout (java.io.StringWriter.)
        stderr (java.io.StringWriter.)
        result (binding [*out* stdout *err* stderr]
                 (try
                   {:printed-value (pr-str (invoke))}
                   (catch Exception error
                     (let [diagnostic
                           (or (some #(when (:aguafria/phase (ex-data %)) %)
                                     (take-while some? (iterate ex-cause error)))
                               error)]
                       {:exception {:class (.getName (class diagnostic))
                                    :message (ex-message diagnostic)
                                    :phase (:aguafria/phase (ex-data diagnostic))}}))))]
    (merge {:namespace (str namespace-symbol)
            :form form :stdout (str stdout) :stderr (str stderr)}
           result)))

(defn capture-comment-repl!
  "Evaluate the authored comment's forms in its own namespace in this JVM.
  There is no file-runner substitution. Context-only excerpts have no calls."
  ([source context] (capture-comment-repl! source context nil))
  ([source context source-path]
  (let [calls (comment-calls source)
        namespace-symbol (second (read-string source))
        namespaces-before (set (map ns-name (all-ns)))]
    (when (find-ns namespace-symbol)
      (throw (ex-info "Cannot capture over an existing lesson namespace"
                      {:namespace namespace-symbol})))
    (try
      (binding [*ns* (create-ns namespace-symbol)
                *example-context* context]
        (refer 'clojure.core)
        (let [load-result
              (when (seq calls)
                (capture-repl-evaluation!
                 namespace-symbol
                 (pr-str (if source-path
                           (list 'load-file source-path)
                           (list 'load-string source)))
                 #(if source-path
                    (clojure.lang.Compiler/load
                     (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. source))
                     source-path (.getName (io/file source-path)))
                    (load-string source))))]
          (if (:exception load-result)
            ;; A rejected definition never reaches the comment recipe. Report
            ;; the actual load failure, not a call that could not have run.
            {:evaluations [load-result] :scope :load-error}
            {:evaluations
             (mapv (fn [form]
                     (capture-repl-evaluation!
                      namespace-symbol
                      (str/trimr
                       (with-out-str
                         (pprint/with-pprint-dispatch pprint/code-dispatch
                           (pprint/pprint form))))
                      #(eval form)))
                   calls)})))
      (finally
        ;; A recipe can require another lesson (for example its object library).
        ;; Retire only lessons this capture created, not any pre-existing REPL
        ;; namespace, so the next independent recipe can load its own source.
        (doseq [created (map ns-name (all-ns))
                :when (and (not (contains? namespaces-before created))
                           (or (= created namespace-symbol)
                               (str/starts-with? (str created) "learn.example.")))]
          (remove-ns created)
          (dosync (alter @#'clojure.core/*loaded-libs* disj created))))))))

(defn capture-example-comment!
  "Capture same-JVM calls, without running known process-terminating lessons
  in the documentation server. The direct call remains in their comment with
  a warning; no standalone program output is presented as REPL output."
  [{:keys [file manifest]} translation context]
  (let [kind (:kind manifest)
        source (slurp (:clojure-path translation))
        upstream-output (io/file "build/doctest/zig" (str file ".html"))
        panics? (and (.isFile upstream-output)
                     (re-find #"thread [0-9]+ panic:"
                              (observed-output (slurp upstream-output))))]
    (cond
      (empty? (comment-calls source)) {:evaluations [] :scope :context-only}
      (or (= "exe=fail" kind)
          (str/starts-with? kind "test_safety=")
          panics?)
      {:evaluations [] :scope :native-failure-requires-disposable-jvm}
      (some #(str/starts-with? % "target=") (:options manifest))
      {:evaluations [] :scope :target-specific}
      :else (capture-comment-repl!
             source context
             (.getCanonicalPath
              (io/file (or (some-> (:authored-source translation) io/resource)
                           (:clojure-path translation))))))))

(def matching-comparisons
  #{:output-matched :diagnostics-matched :compile-only-matched})

(defn verified-comment?
  "A recorded native diagnostic can be the result of a compile-error lesson.
  JVM arity/resolution/loading failures never count as a verified native result.
  Syntax-only excerpts may intentionally fail when their declarations are used."
  [{:keys [kind]} {:keys [scope evaluations]}]
  (or (contains? #{:context-only :native-failure-requires-disposable-jvm
                   :target-specific} scope)
      (and (seq evaluations)
           (every? (fn [{:keys [exception]}]
                     (or (nil? exception)
                         (and (or (= "syntax" kind)
                                  (str/starts-with? kind "test_error=")
                                  (str/starts-with? kind "obj=")
                                  (= "exe=build_fail" kind))
                              (contains? #{:zig-compile :zig-test :zig-program-compile}
                                         (:phase exception)))))
                   evaluations))))

(defn verify-example!
  "Run the original and the emitted displayed Aguafria through the same pinned
  upstream harness and compare its observations. This verifies the documented
  outcome, not exhaustive equivalence for every possible program input."
  [tool example translation]
  (let [{:keys [file manifest]} example
        original (run-doctest! tool file :zig)
        front-end? (= :translated-front-end-error (:status translation))
        front-end-diagnostic
        (when front-end?
          (locking repl-capture-lock
           (let [source (slurp (:clojure-path translation))]
            (try
              (emit-clojure source (second (read-string source)) {})
              nil
              (catch Exception failure (.getMessage failure))))))]
    (if-not (= :translated (:status translation))
      {:file file
       :status (if (and (zero? (:exit original))
                        (or (= :zig-only (:status translation))
                            (and front-end?
                                 (= (:diagnostic translation) front-end-diagnostic))))
                 :reviewed-special-case-passed
                 :not-translated)
       :fingerprint (:fingerprint tool)
       :source-sha256 (:sha256 example)
       :clojure-sha256 (when front-end? (sha256 (:clojure-path translation)))
       :translation-status (:status translation)
       :reason (:reason translation)
       :diagnostic (:diagnostic translation)
       :original original
       :repl-transcript (when front-end?
                          (try
                            (locking repl-capture-lock
                              (capture-example-comment! example translation *example-context*))
                            (catch Exception error
                              {:evaluations [] :scope :front-end-error
                               :load-error (.getMessage error)})))}
      (let [source (slurp (str "build/emitted/" file))]
        (write-text! (str "build/verification-input/" file)
                     (str source (:text manifest)))
        (let [;; Translation already evaluated and emitted the displayed source.
              ;; Native harness processes can run independently. Only the actual
              ;; comment-form capture below mutates JVM namespaces/native output.
              converted (run-doctest! tool file :aguafria)
              harness-passed? (and (zero? (:exit original))
                                   (= 0 (:exit converted)))
              comparison (when harness-passed? (compare-doctest-output file manifest))
              recipe (try
                       (locking repl-capture-lock
                         (capture-example-comment! example translation *example-context*))
                       (catch Exception error
                         {:capture-error (.getMessage error)}))
              recipe-passed? (verified-comment? manifest recipe)
              passed? (and harness-passed? (matching-comparisons (:status comparison))
                           recipe-passed?)
              cross-target? (some #(str/starts-with? % "target=") (:options manifest))]
          {:file file
           :status (if passed? :upstream-outcome-passed :failed)
           :fingerprint (:fingerprint tool)
           :source-sha256 (:sha256 example)
           :clojure-sha256 (sha256 (:clojure-path translation))
           :emitted-sha256 (sha256 (str "build/emitted/" file))
           :comparison comparison
           :execution-scope (cond
                              cross-target? :target-specific-see-harness
                              (= "syntax" (:kind manifest)) :syntax-only
                              (or (= "test" (:kind manifest))
                                  (str/starts-with? (:kind manifest) "exe="))
                              :host
                              :else :compile-or-expected-failure)
           :original original
           :repl-transcript recipe
           :aguafria converted})))))

(defn verify-outcomes!
  ([] (verify-outcomes! (:examples (inventory))))
  ([examples]
   (verify-outcomes! examples {:jobs 4}))
  ([examples {:keys [jobs] :or {jobs 4}}]
   (let [{:keys [tool translations] :as context} (prepare-example-context!)
         overrides (read-edn "resources/learn/overrides.edn")
         results (binding [*example-context* context]
                   (parallel-builds jobs (fn [{:keys [file] :as example}]
                           (let [path (str "build/outcomes/" (example-id file) ".edn")
                                 translation (translations file)
                                 inputs {:compiler (:fingerprint tool)
                                         :sources (example-source-inputs file overrides)}
                                 cached (when (.isFile (io/file path)) (read-edn path))
                                 reuse? (reusable-example? cached inputs translation
                                                           #{:upstream-outcome-passed :reviewed-special-case-passed})
                                 result (if reuse?
                                          cached
                                          (assoc (verify-example! tool example translation)
                                                 :inputs inputs :artifacts (translation-artifacts translation)))]
                             (when-not reuse? (write-edn! path result))
                             (println (if reuse? :cached-outcome (:status result)) file)
                             (flush)
                             result)) examples))
         summary (frequencies (map :status results))
         failures (filterv #(not (contains? #{:upstream-outcome-passed
                                              :reviewed-special-case-passed}
                                            (:status %))) results)]
     (write-edn! "build/outcomes.edn" results)
     (when (seq failures)
       (throw (ex-info "Reference outcome comparison failed"
                       {:summary summary
                        :failures (mapv #(select-keys % [:file :status :comparison]) failures)})))
     summary)))

(defn escape-html [text]
  (str/escape (str text) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;"}))

(def figure-pattern
  #"(?s)<figure><figcaption class=\"zig-cap\"><cite class=\"file\">([^<]+)</cite></figcaption>.*?</figure>(?:\s*<figure><figcaption class=\"shell-cap\">Shell</figcaption>.*?</figure>)?")

(defn repl-evaluation [{:keys [namespace form stdout stderr value printed-value exception]}]
  (str (escape-html (str namespace "=> " form "\n"))
       (escape-html stdout)
       (escape-html stderr)
       (when-let [output (not-empty (str stdout stderr))]
         (when-not (str/ends-with? output "\n") "\n"))
       (escape-html (if exception
                      (str (:class exception) ": " (:message exception) "\n")
                      (str (or printed-value (pr-str value)) "\n")))))

(defn repl-panel [transcript]
  (when-not (= [] (:evaluations transcript))
    (str "<div class=\"learn-repl\" aria-label=\"Recorded REPL evaluation\">"
       "<p class=\"learn-repl-label\">REPL</p><pre><samp>"
       (str/join "\n" (map repl-evaluation (or (:evaluations transcript) [transcript])))
       "</samp></pre></div>")))

(defn example-panel [[original file] translation index]
  (let [id (str "learn-example-" index)
        translated? (contains? #{:translated :translated-front-end-error}
                               (:status translation))
        content (cond
                  translated?
                  (str "<figure class=\"learn-aguafria\">"
                       "<figcaption class=\"clojure-cap\"><cite class=\"file\">"
                       (escape-html (.getName (io/file (or (:authored-source translation)
                                                           (:clojure-path translation)))))
                       "</cite></figcaption><pre><code class=\"language-clojure\">"
                       (escape-html (slurp (:clojure-path translation)))
                       "</code></pre></figure>"
                       (when-let [diagnostic (when-not (:repl-transcript translation)
                                               (:diagnostic translation))]
                         (str "<p>Expected Aguafria diagnostic: "
                              (escape-html diagnostic) "</p>"))
                       (when-let [transcript (:repl-transcript translation)]
                         (repl-panel transcript)))
                  (= :zig-only (:status translation))
                  (str "<p>" (escape-html (:reason translation)) "</p>")
                  :else
                  (str "<p>Translation pending"
                       (when (= :compiler-gap (:status translation))
                         ": compiler support needs attention")
                       ". This is not a ZIG_ONLY exclusion.</p>"))]
    (str "<section class=\"learn-example\" aria-label=\""
         (escape-html file) "\">"
         "<div class=\"learn-tabs\" role=\"tablist\" aria-label=\"Example language\" hidden>"
         "<button role=\"tab\" id=\"" id "-zt\" aria-controls=\"" id
         "-z\" aria-selected=\"true\">Zig</button>"
         "<button role=\"tab\" id=\"" id "-at\" aria-controls=\"" id
         "-a\" aria-selected=\"false\" tabindex=\"-1\">Aguafria Zig</button>"
         "<button role=\"tab\" id=\"" id "-bt\" aria-controls=\"" id "-z " id
         "-a\" aria-selected=\"false\" tabindex=\"-1\">Side by side</button></div>"
         "<div role=\"tabpanel\" id=\"" id "-z\" aria-labelledby=\"" id "-zt\">"
         original "</div>"
         "<div role=\"tabpanel\" id=\"" id "-a\" aria-labelledby=\"" id "-at\" hidden>"
         content "</div></section>")))

(defn current-blocks
  "Only present block evidence for the exact pinned input and displayed source."
  [catalog]
  (let [results (if (.isFile (io/file "build/blocks.edn"))
                  (read-edn "build/blocks.edn") [])
        fingerprint (fragment-fingerprint)
        inputs (into {} (map (juxt :id :source-sha256)) (:snippets catalog))]
    (filterv
     (fn [{:keys [id source-sha256 status clojure-path clojure-sha256
                 emitted-path emitted-sha256] :as result}]
       (and (= source-sha256 (get inputs id))
            (or (= :zig-only status)
                (= :compiler-gap status)
                (and (= fingerprint (:fingerprint result))
                     (.isFile (io/file clojure-path))
                     (.isFile (io/file emitted-path))
                     (= clojure-sha256 (sha256 clojure-path))
                     (= emitted-sha256 (sha256 emitted-path))))))
     results)))

(defn verified-translations [catalog]
  (let [translations (if (.isFile (io/file "build/translations.edn"))
                       (read-edn "build/translations.edn") [])
        fingerprint (compiler-fingerprint)
        overrides (read-edn "resources/learn/overrides.edn")
        inputs (into {} (map (juxt :file :sha256)) (:examples catalog))]
    (mapv
     (fn [{:keys [file id clojure-path authored-source] :as translation}]
       (let [path (str "build/outcomes/" id ".edn")
             outcome (when (.isFile (io/file path)) (read-edn path))
             comparison (get-in outcome [:comparison :status])
             current? (and (or (nil? authored-source)
                               (and clojure-path
                                    (.isFile (io/file clojure-path))
                                    (io/resource authored-source)
                                    (= (slurp (io/resource authored-source))
                                       (slurp clojure-path))))
                           (= fingerprint (:fingerprint outcome))
                           (= {:compiler fingerprint :sources (example-source-inputs file overrides)}
                              (:inputs outcome))
                           (= (inputs file) (:source-sha256 outcome)))
             verified? (and current?
                            (= :upstream-outcome-passed (:status outcome))
                            (matching-comparisons comparison)
                            clojure-path
                            (.isFile (io/file clojure-path))
                            (.isFile (io/file "build/emitted" file))
                            (= (:clojure-sha256 outcome) (sha256 clojure-path))
                            (= (:emitted-sha256 outcome)
                               (sha256 (io/file "build/emitted" file))))]
         (cond-> (dissoc translation :verification :repl-transcript)
           verified? (assoc :verification comparison
                            :repl-transcript (:repl-transcript outcome))
           (and current?
                (= :reviewed-special-case-passed (:status outcome))
                (or (= :zig-only (:status translation))
                    (and clojure-path
                         (.isFile (io/file clojure-path))
                         (= (:clojure-sha256 outcome) (sha256 clojure-path)))))
           (assoc :verification :reviewed-special-case-passed
                  :repl-transcript (:repl-transcript outcome)))))
     translations)))

(defn original-document
  "Remove only the learning UI, retaining the original HTML without reserialization."
  [html]
  (-> html
      (str/replace (str "<style>" (slurp (io/resource "learn/reference.css")) "</style>") "")
      (str/replace #"(?s)<script>window\.Prism = \{manual: true\};.*?</script>" "")
      (str/replace
       #"(?s)<section class=\"learn-example\"[^>]*><div class=\"learn-tabs\".*?</div><div role=\"tabpanel\" id=\"learn-example-[0-9]+-z\"[^>]*>(.*?)</div><div role=\"tabpanel\" id=\"learn-example-[0-9]+-a\"[^>]*>.*?</div></section>"
       (fn [[_ original]] original))
      (str/replace
       #"(?s)<span class=\"learn-inline\"[^>]*><span data-language=\"zig\">(.*?)</span><span data-language=\"aguafria\" hidden>.*?<span class=\"learn-inline-note\"[^>]*>.*?</span></span>"
       (fn [[_ original]] original))))

(defn build! []
  (let [catalog (inventory)
        inline-snippets (current-inlines
                         (inline/translate (filterv #(= "syntax" (:kind %))
                                                    (:snippets catalog))))
        blocks (current-blocks catalog)
        translations (verified-translations catalog)
        by-file (into {} (map (juxt :file identity)) translations)
        by-file (into by-file
                      (map (juxt :file identity))
                      (remove #(= :zig-only (:status %)) blocks))
        original (slurp (io/file upstream-dir "index.html"))
        annotated (inline/annotate original inline-snippets decode-html escape-html)
        _ (when (seq (:unmatched annotated))
            (throw (ex-info "Cannot pair the published HTML with the pinned snippets"
                            {:unmatched (mapv :id (:unmatched annotated))})))
        index (atom 0)
        html (str/replace (:html annotated) figure-pattern
                          (fn [[_ file :as match]]
                            (example-panel match (get by-file file) (swap! index inc))))
        css (slurp (io/resource "learn/reference.css"))
        ;; Bundle just the pinned core and Clojure grammar. No CDN, network
        ;; dependency, or automatic rewriting of the original Zig figures.
        js (str "window.Prism = {manual: true};\n"
                (slurp (io/resource "learn/vendor/prism/prism-core.min.js")) "\n"
                (slurp (io/resource "learn/vendor/prism/prism-clojure.js")) "\n"
                (slurp (io/resource "learn/reference.js")))
        html (-> html
                 (str/replace "</head>" (str "<style>" css "</style></head>"))
                 (str/replace "</body>" (str "<script>" js "</script></body>")))
        _ (when-not (= original (original-document html))
            (throw (ex-info "Learning additions changed the original reference HTML" {})))
        coverage {:sections (count (:sections catalog))
                  :file-examples (count (:examples catalog))
                  :code-references (count (:code-references catalog))
                  :tabbed-figures @index
                  :snippet-count (count (:snippets catalog))
                  :inline-status (frequencies (map :status inline-snippets))
                  :inline-verification (frequencies (map :verification inline-snippets))
                  :inline-html-matched (count (:matched-ids annotated))
                  :upstream-inline-differences
                  (mapv #(select-keys % [:id :upstream-difference])
                        (filter :upstream-difference inline-snippets))
                  :block-status (frequencies (map :status blocks))
                  :block-verification (frequencies (map :verification blocks))
                  :repl-transcripts
                  (count (filter #(seq (get-in % [:repl-transcript :evaluations]))
                                 translations))
                  :repl-scopes
                  (frequencies
                   (keep #(when-let [transcript (:repl-transcript %)]
                            (or (:scope transcript) :in-process))
                         translations))
                  :hand-written-examples
                  (count (filter :source (vals (read-edn "resources/learn/overrides.edn"))))
                  :file-verification (frequencies (map :verification translations))
                  :translation-status (frequencies (map :status translations))}]
    (write-edn! "build/inventory.edn" catalog)
    (write-edn! "build/inline.edn" inline-snippets)
    (write-edn! "build/coverage.edn" coverage)
    (write-text! "build/site/index.html" html)
    coverage))

(defn acceptance-problems
  [catalog translations blocks inlines overrides]
  (let [same-ids? (fn [key expected actual]
                    (and (= (count expected) (count actual))
                         (= (set (map key expected)) (set (map key actual)))))
        expected-blocks (filter #(= "syntax_block" (:kind %)) (:snippets catalog))
        expected-inlines (filter #(= "syntax" (:kind %)) (:snippets catalog))
        unresolved (filter #(not (contains? #{:translated :translated-front-end-error :zig-only}
                                            (:status %))) translations)
        verified-file? (fn [translation]
                         (contains? (conj matching-comparisons :reviewed-special-case-passed)
                                    (:verification translation)))
        verified-block? (fn [{:keys [status verification context-review]}]
                          (or (= :zig-only status)
                              (and (= :translated status)
                                   (or (contains? #{:output-matched :discovery-matched} verification)
                                       (and (= :syntax-roundtrip-passed verification)
                                            (not (str/blank? context-review)))))))
        verified-inline? (fn [{:keys [status verification]}]
                           (and (contains? #{:translated :reference-mapped} status)
                                (contains? #{:output-matched :syntax-emission-passed
                                             :reviewed-syntax-note :catalog-var-matched
                                             :type-emission-matched :literal-emission-matched
                                             :identifier-emission-matched} verification)))]
    (cond-> []
      (not (same-ids? :file (:examples catalog) translations))
      (conj :missing-file-translations)

      (seq unresolved)
      (conj :unresolved-translations)

      (not-every? verified-file? translations)
      (conj :unverified-file-outcomes)

      (some #(and (not= :zig-only (:status %))
                  (nil? (:repl-transcript %))) translations)
      (conj :missing-repl-transcripts)

      (not (same-ids? :id expected-blocks blocks))
      (conj :missing-block-translations)

      (not-every? verified-block? blocks)
      (conj :unverified-block-translations)

      (not (same-ids? :id expected-inlines inlines))
      (conj :missing-inline-translations)

      (not-every? verified-inline? inlines)
      (conj :unverified-inline-translations)

      (some #(and (not= :zig-only (:status %))
                  (nil? (:source (get overrides (:file %))))) translations)
      (conj :hand-written-examples-pending))))

(defn verify! []
  (let [coverage (build!)
        catalog (read-edn "build/inventory.edn")
        problems (acceptance-problems catalog
                                      (verified-translations catalog)
                                      (current-blocks catalog)
                                      (read-edn "build/inline.edn")
                                      (read-edn "resources/learn/overrides.edn"))]
    (when (seq problems)
      (throw (ex-info "Reference acceptance is incomplete"
                      {:coverage coverage :problems problems})))
    coverage))

(defn serve! [port]
  ;; Serving a verified snapshot must not rebuild it against a newer compiler
  ;; and silently discard the captured REPL output as stale.
  (when-not (.isFile (io/file "build/site/index.html"))
    (build!))
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" port) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (let [path (.getPath (.getRequestURI exchange))
                              found? (contains? #{"/" "/index.html"} path)
                              body (.getBytes
                                    (if found? (slurp "build/site/index.html") "Not found")
                                    "UTF-8")]
                          (.set (.getResponseHeaders exchange) "Content-Type"
                                "text/html; charset=utf-8")
                          (.sendResponseHeaders exchange (if found? 200 404) (alength body))
                          (with-open [out (.getResponseBody exchange)]
                            (.write out body))))))
    (.start server)
    (println (str "Learning reference: http://127.0.0.1:" (.getPort (.getAddress server)) "/"))
    server))

(defn -main [& [command argument]]
  (case command
    "snapshot" (snapshot! argument)
    "inventory" (do (write-edn! "build/inventory.edn" (inventory))
                      (println "Wrote build/inventory.edn"))
    "translate" (prn (translate!))
    "blocks" (prn (translate-blocks!))
    "inlines" (prn (verify-inlines!))
    "outcomes" (prn (verify-outcomes! (:examples (inventory))
                                    {:jobs (if argument (Integer/parseInt argument) 4)}))
    "build" (prn (build!))
    "verify" (verify!)
    "serve" (serve! (if argument (Integer/parseInt argument) 8096))
    (throw (ex-info "Expected snapshot, inventory, translate, blocks, inlines, outcomes, build, verify or serve"
                    {:command command}))))
