(ns aguafria.zig.bundle
  "One immutable AOT library per preparation, indexed by ordinary artifact keys."
  (:require [aguafria.zig.artifact :as artifact]
            [aguafria.zig.explain :as explain]
            [aguafria.zig.source-scanner :as scanner]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [java.lang.foreign Arena SymbolLookup]
           [java.nio.channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption StandardOpenOption]
           [java.util UUID]))

(def ^:private version 3)
(def ^:dynamic *preparing* nil)
(def ^:dynamic *batch-validation?* false)
(def ^:dynamic *validation-dependencies* nil)
(def ^:private validation-batch-limit 256)
(defonce ^:private locks (atom {}))
(defonce ^:private images (atom {}))
(defonce ^:private loaded-artifacts (atom {}))

(defn- lock-for [key]
  (get (swap! locks #(if (contains? % key) % (assoc % key (Object.)))) key))

(defn- file? [path]
  (and path (.isFile (io/file path)) (pos? (.length (io/file path)))))

(defn- read-edn [path]
  (when (file? path)
    (try (edn/read-string (slurp path))
         (catch java.io.IOException _ nil)
         (catch RuntimeException _ nil))))

(defn- publish-edn! [path value]
  (io/make-parents path)
  (let [temporary (io/file (.getParentFile (io/file path)) (str "." (UUID/randomUUID) ".edn"))]
    (try
      (spit temporary (artifact/print-data value))
      (Files/move (.toPath temporary) (.toPath (io/file path))
                  (into-array java.nio.file.CopyOption
                              [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
      (finally (Files/deleteIfExists (.toPath temporary))))))

(defn- artifact-id [{:keys [module hash]}]
  (artifact/key-for :bundle-entry [module hash]))

(defn- index-file [cache-dir artifact]
  (io/file cache-dir "bundles" "index" (str (artifact-id artifact) ".edn")))

(defn- cache-root [cache-dir]
  (str (.normalize (.toAbsolutePath (.toPath (io/file cache-dir))))))

(defn find-artifact
  "Reuse an exact entry in a loaded pack before one direct disk-index lookup.
  Missing/incomplete packs are cache misses, not scans."
  [cache-dir artifact]
  (let [root (cache-root cache-dir)
        key (artifact-id artifact)]
    (or (get-in @loaded-artifacts [root key])
        (let [{:keys [id prefix exports library-bytes debug-bytes] :as entry}
              (read-edn (index-file cache-dir artifact))]
          (when (and (= version (:version entry))
                     ;; This index accepts only the ordinary ABI. Unknown
                     ;; experimental backend entries are cache misses.
                     (nil? (:backend entry))
                     (= key (:artifact entry))
                     (string? id) (re-matches #"[a-f0-9]{64}" id)
                     (string? prefix) (vector? exports) (every? string? exports))
            (let [directory (io/file cache-dir "bundles" id)
                  library (io/file directory (System/mapLibraryName "aguafria_bundle"))
                  debug (io/file (str library ".dwarf"))]
              (when (and (file? library) (= library-bytes (.length library))
                         (or (nil? debug-bytes)
                             (and (file? debug) (= debug-bytes (.length debug)))))
                (assoc entry :cache-root root :library (.getAbsolutePath library)
                       :debug (when debug-bytes (.getAbsolutePath debug))))))))))

(defn- retain-loaded-artifacts! [entry]
  (let [manifest (read-edn (io/file (.getParentFile (io/file (:library entry)))
                                    "manifest.edn"))]
    (when (and (:cache-root entry)
               (= version (:version manifest))
               (= (:id entry) (:id manifest))
               (= (:library-bytes entry) (:library-bytes manifest)))
      (let [common (merge (dissoc manifest :entries)
                          (select-keys entry [:cache-root :library :debug]))
            entries (into {}
                          (keep (fn [[key {:keys [prefix exports] :as item}]]
                                  (when (and (string? key) (re-matches #"[a-f0-9]{64}" key)
                                             (string? prefix) (vector? exports)
                                             (nil? (:backend item))
                                             (every? string? exports))
                                    [key (merge common item {:artifact key})])))
                          (:entries manifest))]
        ;; Existing loaded exports stay usable even if a later preparation
        ;; points the disk index at a different, overlapping pack.
        (swap! loaded-artifacts update (:cache-root entry)
               #(merge entries %))))))

(defn- analyze-source [source]
  (scanner/analyze! source))

(defn- lexical-source-analysis [source key]
  (if-not *preparing*
    (analyze-source source)
    (locking *preparing*
      (or (get-in @*preparing* [:lexical-source-analyses key])
          (let [analysis (analyze-source source)]
            (swap! *preparing* assoc-in [:lexical-source-analyses key] analysis)
            analysis)))))

(defn- mask-linked-support [source fragments]
  (let [bytes (.getBytes ^String source StandardCharsets/UTF_8)]
    (doseq [fragment fragments
            :let [length (alength (.getBytes ^String fragment StandardCharsets/UTF_8))]]
      (loop [from 0]
        (let [start (.indexOf ^String source ^String fragment (int from))]
          (when (not (neg? start))
            (let [offset (alength (.getBytes ^String (subs source 0 start) StandardCharsets/UTF_8))]
              (java.util.Arrays/fill bytes (int offset) (int (+ offset length)) (byte 32)))
            (recur (+ start (count fragment)))))))
    (String. bytes StandardCharsets/UTF_8)))

(defn- library-source-analysis
  ([source] (library-source-analysis source []))
  ([source linked-support-fragments]
   (library-source-analysis source linked-support-fragments
                            (artifact/key-for :bundle-source source)))
  ([source linked-support-fragments key]
   (let [analysis (lexical-source-analysis source key)
         asset-import? (some (fn [[kind argument]]
                               (or (= "embedFile" kind) (nil? argument)
                                   (and argument
                                        (or (str/includes? argument "/")
                                            (str/ends-with? argument ".zig\"")))))
                             (:imports analysis))
         masked-source (when (or asset-import? (:external-declaration-syntax? analysis))
                         (mask-linked-support source linked-support-fragments))
         application-extern? (and (:external-declaration-syntax? analysis)
                                  (:external-declaration-syntax?
                                   (lexical-source-analysis
                                    masked-source (artifact/key-for :bundle-source masked-source))))
         parsed (when (or asset-import? application-extern?)
                  ((requiring-resolve 'aguafria.zig.convert/parse-source)
                   masked-source))
        ;; Only supplier-validated support spans are omitted from this trigger.
        ;; Original imports/exports still determine graph eligibility. Zig's
        ;; AST distinguishes external function/storage declarations from layout
        ;; qualifiers and text. An external declaration requires real linking,
        ;; not rejection: it may be unused or supplied by this exact graph.
         external-declarations?
         (boolean
          (or (some (fn [declaration]
                      (when-some [token (nth declaration 5 nil)]
                        (= :keyword_extern (get-in parsed [:tokens token 0]))))
                    (vals (:function-prototype-index parsed)))
              (some (fn [declaration]
                      (when-some [token (nth declaration 2 nil)]
                        (= :keyword_extern (get-in parsed [:tokens token 0]))))
                    (vals (:var-index parsed)))))
         analysis (assoc analysis :external-declarations? external-declarations?)]
     (if-not asset-import?
       analysis
       (let [tests (mapv (fn [index]
                           (let [{:keys [first-token last-token]} (nth (:nodes parsed) index)
                                 [_ start] (nth (:tokens parsed) first-token)
                                 [_ end length] (nth (:tokens parsed) last-token)]
                             [start (+ end length)]))
                         (keys (:test-index parsed)))]
         (if-not (seq tests)
           analysis
           (let [bytes (.getBytes ^String source StandardCharsets/UTF_8)]
            ;; build-lib ignores test-only embedFile calls, but Zig still
            ;; resolves test imports. Mask only AST-confirmed embedded calls
            ;; for eligibility; the emitted source remains unchanged.
             (doseq [index (keys (:builtin-index parsed))
                     :let [{:keys [first-token last-token]} (nth (:nodes parsed) index)
                           [_ start token-length] (nth (:tokens parsed) first-token)
                           [_ end length] (nth (:tokens parsed) last-token)]
                     :when (and (= "@embedFile"
                                   (String. bytes (int start) (int token-length)
                                            StandardCharsets/UTF_8))
                                (some (fn [[test-start test-end]]
                                        (<= test-start start (+ end length) test-end))
                                      tests))]
               (java.util.Arrays/fill bytes (int start) (int (+ end length)) (byte 32)))
             (assoc (scanner/analyze! (String. bytes StandardCharsets/UTF_8))
                    :external-declarations? external-declarations?))))))))

(defn- source-analysis [source key linked-support]
  ;; Both caches retain facts/spans only, for one preparation. Raw lexical facts
  ;; also serve validation and final rewriting without tokenizing twice.
  (let [analysis-key [key (artifact/key-for :bundle-linked-support linked-support)]]
    (if-not *preparing*
      (library-source-analysis source (:fragments linked-support) key)
      (locking *preparing*
        (or (get-in @*preparing* [:source-analyses analysis-key])
            (let [analysis (library-source-analysis source (:fragments linked-support) key)]
              (swap! *preparing* assoc-in [:source-analyses analysis-key] analysis)
              analysis))))))

(defn- linked-compiler-support [artifact]
  (let [{:keys [path hash fragments] :as support} (:compiler-owned-linkage-support artifact)]
    ;; This provenance comes from the runtime's compiler-owned support build,
    ;; not an extern name. It applies only with that immutable image present in
    ;; the actual link command. User declarations outside exact source spans
    ;; remain subject to the real linker even when they use the same names.
    (when (and (= path (:development-panic-support-path artifact))
               (some #{path} (:command artifact)) (file? path)
               (string? hash) (not (str/blank? hash))
               (vector? fragments) (every? #(and (string? %) (not (str/blank? %))) fragments))
      support)))

(defn- rename-identifiers [source renames]
  (scanner/rewrite source (analyze-source source) renames nil))

(defn- bind-root-imports [source module-name]
  (scanner/rewrite source (analyze-source source) {} module-name))

(defn- validate-source! [{:keys [deps analysis]}]
  (let [allowed (into #{"std" "builtin" "root"}
                      (map #(first (str/split % #"=" 2))) deps)]
    (when (:external-exports? analysis)
      (throw (ex-info "Bundle requires compiler-owned function exports" {:reason :external-exports})))
    (when (:dynamic-exports? analysis)
      (throw (ex-info "Dynamic exports require standalone linking" {:reason :dynamic-exports})))
    (doseq [[builtin argument] (:imports analysis)]
      (when-not (and (= "import" builtin)
                     (some #(= argument (artifact/print-data %)) allowed))
        (throw (ex-info "Relative assets or dynamic imports require standalone linking"
                        {:reason :relative-or-dynamic-assets}))))))

(defn- module-groups [{:keys [command development-panic-support-path]}]
  (loop [remaining (drop 4 command), deps [], flags [], groups [], link-args []]
    (if-let [argument (first remaining)]
      (cond
        (= argument development-panic-support-path)
        (recur (next remaining) deps flags groups link-args)

        (= argument "--dep")
        (recur (nnext remaining) (conj deps (second remaining)) flags groups link-args)

        (str/starts-with? argument "-M")
        (let [[name path] (str/split (subs argument 2) #"=" 2)]
          (recur (next remaining) [] []
                 (conj groups {:name name :path path :deps deps :flags flags}) link-args))

        (or (#{"-Odebug" "-Osafe" "-Ofast" "-Osmall"
               "-ferror-tracing" "-funwind-tables" "-fPIC"} argument)
            (re-matches #"-(?:I|D).+" argument))
        (recur (next remaining) deps (conj flags argument) groups link-args)

        (#{"-I" "-isystem" "-D"} argument)
        (if-let [value (second remaining)]
          (recur (nnext remaining) deps (into flags [argument value]) groups link-args)
          (throw (ex-info "Missing compiler argument" {:reason :compiler-arguments :argument argument})))

        (= "-framework" argument)
        (if-let [framework (second remaining)]
          (recur (nnext remaining) deps flags groups (into link-args [argument framework]))
          (throw (ex-info "Missing framework name" {:reason :compiler-arguments :argument argument})))

        (or (re-matches #"-[lLF].+" argument)
            (and (.isAbsolute (io/file argument)) (file? argument)
                 (re-find #"\.(?:a|dylib|so(?:\.[0-9]+)*|lib|o|obj)$" argument)))
        (recur (next remaining) deps flags groups (conj link-args argument))

        :else (throw (ex-info "Compiler configuration requires standalone linking"
                              {:reason :compiler-arguments :argument argument})))
      (if (or (seq deps) (seq flags) (empty? groups))
        (throw (ex-info "Incomplete module graph" {:reason :module-graph}))
        {:groups groups :link-args link-args}))))

(defn candidate
  "Describe an emitted compiler graph eligible for the shared AOT image."
  [artifact]
  (when (or (:jvm-adapter? artifact) (:jvm-wrapper? artifact)
            (:jvm-namespace-image? artifact))
    (try
      (when (or (not= :shared (:development-panic artifact)) (:native-test-context? artifact))
        (throw (ex-info "Handler requires standalone linking"
                        {:reason (if (:native-test-context? artifact) :native-test-context :panic-profile)})))
      (let [{:keys [groups link-args]} (module-groups artifact)
            linked-support (linked-compiler-support artifact)
            groups (mapv (fn [group]
                           (let [source (slurp (:path group))
                                 key (artifact/key-for :bundle-source source)
                                 analysis (source-analysis source key linked-support)
                                 proof (get-in artifact [:compiler-owned-shared-modules (:name group)])
                                 shared-support
                                 (when (and (= :stateless-jvm-transport (:kind proof))
                                            (= (:name group) (:module proof))
                                            (= key (:source-key proof))
                                            (not= "root" (:name group))
                                            (empty? (:deps group))
                                            (empty? (:exports analysis))
                                            (= #{["import" "\"std\""]} (set (:imports analysis))))
                                   proof)]
                             (assoc group :source source :source-key key
                                    :shared-support shared-support
                                    :implicit-root?
                                    (and (some #{["import" "\"root\""]}
                                               (:imports analysis))
                                         (not-any? #(= "root" (first (str/split % #"=" 2)))
                                                   (:deps group)))
                                    :analysis analysis))) groups)
            _ (doseq [group groups] (validate-source! group))
            names (->> groups (mapcat #(get-in % [:analysis :exports])) distinct sort vec)
            forwarder (first (str/split (:source (first groups))
                                        #"// Aguafria development loader\." 2))]
        (when (or (seq names) (:jvm-namespace-image? artifact))
          (assoc artifact
                 :groups (mapv #(-> %
                                    (dissoc :source :analysis)) groups)
                 :requires-link-validation?
                 (boolean (some #(get-in % [:analysis :external-declarations?]) groups))
                 :link-args link-args :exports names :forwarder forwarder)))
      (catch clojure.lang.ExceptionInfo error
        (when *preparing*
          (swap! *preparing* assoc-in [:excluded (artifact-id artifact)]
                 {:module (:module artifact) :reason (:reason (ex-data error))}))
        nil))))

(defn prepared-artifact
  "Return an artifact already checked successfully in this preparation run."
  [artifact]
  (when *preparing*
    (get-in @*preparing* [:artifacts (artifact-id artifact)])))

(defn observe! [artifact]
  (when *preparing*
    (swap! *preparing* assoc-in [:artifacts (artifact-id artifact)] artifact)
    (when (and *validation-dependencies* (:validation-pending? artifact))
      (swap! *validation-dependencies* conj (artifact-id artifact))))
  artifact)

(defn call-with-validation-scope
  "Collect exact queued dependencies of one preparation result. The result
  remains pending until compiler validation, including its cleanup adapters."
  [prepare]
  (if-not (and *preparing* *batch-validation?*)
    (prepare)
    (let [dependencies (atom #{})
          parent *validation-dependencies*
          retry (bound-fn [] (binding [*batch-validation?* false] (prepare)))
          result (binding [*validation-dependencies* dependencies] (prepare))]
      (when parent (swap! parent into @dependencies))
      (if (and (map? result) (seq @dependencies))
        (let [id (:scope-sequence (swap! *preparing* update :scope-sequence (fnil inc 0)))]
          (swap! *preparing* assoc-in [:validation-scopes id]
                 {:dependencies @dependencies :retry retry})
          (cond-> (assoc result :validation-scope id)
            (= :prepared (:status result)) (assoc :status :pending-validation)))
        result))))

(defn resolve-validation-report!
  "Resolve pending report records only after their exact dependencies pass.
  Rejected scopes retry the original generator for its ordinary diagnostics and
  test-context handling. No compiler or native validation is bypassed."
  [collected report error-report]
  (let [resolved (atom {})]
    (walk/postwalk
     (fn [record]
       (if-let [id (and (map? record) (:validation-scope record))]
         (let [{:keys [dependencies retry]} (get-in @collected [:validation-scopes id])
               _ (when-not (seq dependencies)
                   (throw (ex-info "Missing preparation validation scope" {:scope id})))
               accepted? (every? #(= :validated (get-in @collected [:validation-results % :status]))
                                 dependencies)
               replacement (when-not accepted?
                             (or (get @resolved id)
                                 (let [result (try (retry)
                                                   (catch Exception error
                                                     (assoc (error-report error) :status :failed)))]
                                   (swap! resolved assoc id result)
                                   result)))]
           (cond-> (dissoc record :validation-scope)
             (and accepted? (= :pending-validation (:status record))) (assoc :status :prepared)
             replacement (merge replacement)))
         record)) report)))

(defn- shared-support-key [group]
  (when-let [proof (:shared-support group)]
    [proof (:flags group) (.getName (io/file (:path group)))]))

(defn- materialize-entry! [directory artifact {:keys [shared-names written-support]}]
  (let [id (artifact-id artifact)
        prefix (str "pack_" id "_")
        groups (:groups artifact)
        names (into {} (map-indexed #(vector (:name %2)
                                             (or (get shared-names (shared-support-key %2))
                                                 (str "m_" id "_" %1))) groups))
        renames (into {} (map #(vector % (str prefix %))) (:exports artifact))]
    (assoc artifact :prefix prefix :entry (names "root")
           :groups
           (mapv (fn [{:keys [name deps source-key implicit-root?] :as group}]
                   ;; Zig type names include the file basename. Preserve it,
                   ;; rather than leaking bundle IDs into reflected type names.
                   (let [source (slurp (:path group))
                         _ (when-not (= source-key (artifact/key-for :bundle-source source))
                             (throw (ex-info "Prepared bundle source changed before linking"
                                             {:aguafria/phase :bundle-compile
                                              :path (:path group) :module name})))
                         path (io/file directory (names name) (.getName (io/file (:path group))))
                         shared? (contains? shared-names (shared-support-key group))]
                     (io/make-parents path)
                     (when-not (and shared? (contains? @written-support (shared-support-key group)))
                       (spit path (scanner/rewrite source (lexical-source-analysis source source-key)
                                                   (if shared? {} renames)
                                                   (when implicit-root? (names "root"))))
                       (when shared? (vswap! written-support conj (shared-support-key group))))
                     (assoc group :new-name (names name) :new-path (.getAbsolutePath path)
                            :new-deps
                            (mapv (fn [dependency]
                                    (let [[alias target] (str/split dependency #"=" 2)
                                          target (or target alias)]
                                      (when-not (names target)
                                        (throw (ex-info "Missing bundle module" {:dependency dependency})))
                                      (str alias "=" (names target))))
                                  ;; Zig's implicit root is per original graph,
                                  ;; not the aggregate library's module.
                                  (cond-> deps
                                    implicit-root? (conj (str (names "root") "=root")))))))
                 groups))))

(defn- materialize-entries! [directory artifacts]
  (let [repeated (->> artifacts (mapcat :groups) (keep shared-support-key) frequencies)
        shared-names (into {}
                           (keep (fn [[key count]]
                                   (when (> count 1)
                                     [key (str "support_" (artifact/key-for :bundle-support key))])))
                           repeated)
        context {:shared-names shared-names :written-support (volatile! #{})}]
    (mapv #(materialize-entry! directory % context) artifacts)))

(defn- unique-compilation-groups [entries]
  (loop [pending (seq (mapcat :groups entries)), by-name {}, groups []]
    (if-let [{:keys [new-name] :as group} (first pending)]
      (if-let [previous (get by-name new-name)]
        (do
          (when-not (= (select-keys previous [:source-key :flags :new-deps :new-path])
                       (select-keys group [:source-key :flags :new-deps :new-path]))
            (throw (ex-info "Shared compiler support has inconsistent inputs"
                            {:module new-name})))
          (recur (next pending) by-name groups))
        (recur (next pending) (assoc by-name new-name group) (conj groups group)))
      groups)))

(defn- response-argument [argument]
  ;; Zig response files use Args.IteratorGeneral quoting.
  ;; Backslashes are doubled only before a quote or the closing delimiter.
  ;; Its single-quote mode cannot faithfully encode a literal apostrophe.
  (when (or (str/includes? argument "'") (str/includes? argument "\u0000"))
    (throw (ex-info "Argument cannot be represented in a Zig response file"
                    {:reason :response-file-argument :argument argument})))
  (str "\""
       (str/replace argument #"(\\*)(\"|$)"
                    (fn [[_ slashes quote]]
                      (str slashes slashes (when (= quote "\"") "\\\""))))
       "\""))

(defn- run-compiler! [run-command command directory]
  (let [response (io/file directory "arguments.rsp")
        _ (spit response (str/join "\n" (map response-argument (drop 2 command))))
        result (run-command [(first command) (second command)
                             (str "@" (.getAbsolutePath response))]
                            (str directory))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Native bundle compilation failed"
                      (assoc result :command (vec command) :aguafria/phase :bundle-compile))))
    result))

(defn- graph-command [artifacts entries link-args source output]
  (let [first-artifact (first artifacts)]
    (vec (concat [(first (:command first-artifact)) "build-lib" "-dynamic" output
                  (:development-panic-support-path first-artifact)]
                 link-args
                 (get-in entries [0 :groups 0 :flags])
                 (mapcat #(vector "--dep" (:entry %)) entries)
                 [(str "-Mroot=" source)]
                 (mapcat (fn [{:keys [flags new-deps new-name new-path]}]
                           (concat flags (mapcat #(vector "--dep" %) new-deps)
                                   [(str "-M" new-name "=" new-path)]))
                         (unique-compilation-groups entries))))))

(defn- write-graph-root! [source artifacts entries]
  (spit source (str (:forwarder (first artifacts)) "\ncomptime {\n"
                    (apply str (map #(str "    _ = @import(\"" (:entry %) "\");\n") entries))
                    "}\n")))

(defn- validate-batch! [cache-dir artifacts run-command]
  (let [id (artifact/key-for :bundle-validation (mapv artifact-id artifacts))
        directory (.getAbsoluteFile (io/file cache-dir "validation" id))]
    (.mkdirs directory)
    (locking (lock-for (.getAbsolutePath directory))
      (with-open [channel (FileChannel/open (.toPath (io/file directory ".lock"))
                                            (into-array java.nio.file.OpenOption
                                                        [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
                  file-lock (.lock channel)]
        (let [entries (materialize-entries! directory artifacts)
              source (io/file directory "validate.zig")
              command (graph-command artifacts entries (:link-args (first artifacts))
                                     source "-fno-emit-bin")]
          (write-graph-root! source artifacts entries)
          (try
            (run-compiler! run-command command directory)
            true
            (catch clojure.lang.ExceptionInfo error
              (if (number? (:exit (ex-data error))) false (throw error)))))))))

(defn validate-pending!
  "Validate bounded batches without linking or publishing artifacts. A failed
  batch is split iteratively; isolated failures use their original compiler
  command. Native invocation and disk-index publication remain separate."
  [cache-dir collected {:keys [run-command validate-artifact]}]
  (let [started (System/nanoTime)
        artifacts (->> (:artifacts @collected) vals
                       (filter :validation-pending?) (keep candidate)
                       (sort-by artifact-id) vec)
        groups (group-by #(vector (first (:command %))
                                  (:development-panic-support-path %)
                                  (:forwarder %) (:link-args %)) artifacts)
        batches (into [] (mapcat #(partition-all validation-batch-limit %))
                      (vals groups))
        results (atom {})
        commands (atom 0)
        rechecks (atom 0)]
    (loop [pending batches]
      (when-let [batch (peek pending)]
        (when (> @commands (* 2 (count artifacts)))
          (throw (ex-info "Validation batch isolation exceeded its bound" {})))
        (swap! commands inc)
        (if (validate-batch! cache-dir (vec batch) run-command)
          (do (swap! results into (map #(vector (artifact-id %) {:status :validated}) batch))
              (recur (pop pending)))
          (if (= 1 (count batch))
            (let [artifact (first batch)]
              (swap! rechecks inc)
              (swap! results assoc (artifact-id artifact) (validate-artifact artifact))
              (recur (pop pending)))
            (let [middle (quot (count batch) 2)
                  batch (vec batch)]
              (recur (conj (pop pending) (subvec batch 0 middle) (subvec batch middle))))))))
    (swap! collected
           (fn [state]
             (reduce-kv
              (fn [state id {:keys [status]}]
                (if (= :validated status)
                  (assoc-in state [:artifacts id :validation-pending?] false)
                  (update state :artifacts dissoc id)))
              (update state :validation-results merge @results) @results)))
    (let [report {:candidates (count artifacts) :batch-limit validation-batch-limit
                  :compiler-batches @commands :individual-rechecks @rechecks
                  :statuses (frequencies (map :status (vals @results)))
                  :duration-ms (/ (- (System/nanoTime) started) 1e6)}]
      (swap! collected assoc :validation report)
      (explain/event! (assoc report :event :batch-validation))
      report)))

(defn- build-pack!
  [cache-dir artifacts link-args {:keys [run-command preserve-debug! accept-validated!]}]
  (let [artifacts (vec (sort-by artifact-id artifacts))
        id (artifact/key-for :bundle [version (mapv artifact-id artifacts)])
        directory (.getAbsoluteFile (io/file cache-dir "bundles" id))
        manifest-file (io/file directory "manifest.edn")]
    (.mkdirs directory)
    (locking (lock-for (.getAbsolutePath directory))
      (with-open [channel (FileChannel/open (.toPath (io/file directory ".lock"))
                                            (into-array java.nio.file.OpenOption
                                                        [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
                  file-lock (.lock channel)]
        (let [existing (read-edn manifest-file)
              library (io/file directory (System/mapLibraryName "aguafria_bundle"))
              debug (io/file (str library ".dwarf"))
              first-artifact (first artifacts)
              debug? (some? (:debug-format first-artifact))
              entries (when-not existing (materialize-entries! directory artifacts))]
          (when existing
            (when-not (and (= (:library-bytes existing) (.length library))
                           (or (not debug?) (= (:debug-bytes existing) (.length debug))))
              (throw (ex-info "Existing immutable bundle is damaged; use a fresh cache directory"
                              {:bundle id :path (str directory)}))))
          (when-not existing
            (let [source (io/file directory "bundle.zig")
                  temporary (io/file directory (str "." (UUID/randomUUID) "-" (.getName library)))
                  command (graph-command artifacts entries link-args source
                                         (str "-femit-bin=" temporary))]
              (write-graph-root! source artifacts entries)
              (try
                (run-compiler! run-command command directory)
                (preserve-debug! temporary library (:debug-format first-artifact))
                (Files/move (.toPath temporary) (.toPath library)
                            (into-array java.nio.file.CopyOption [StandardCopyOption/ATOMIC_MOVE]))
                (finally (Files/deleteIfExists (.toPath temporary))))))
          (let [manifest (or existing
                             {:version version :id id :library-bytes (.length library)
                              :debug-bytes (when debug? (.length debug))
                              :entries (into {} (for [entry entries]
                                                  [(artifact-id entry)
                                                   (select-keys entry [:prefix :exports])]))})]
            ;; A successful full build proves semantic validation before any
            ;; manifest/index can make the queued handlers visible to readers.
            (when accept-validated! (accept-validated! artifacts (boolean existing)))
            (when-not existing (publish-edn! manifest-file manifest))
            ;; Publish pointers last. Concurrent readers see either a complete
            ;; old pack or a complete new pack, never a half-linked library.
            (doseq [artifact artifacts]
              (publish-edn! (index-file cache-dir artifact)
                            (merge (dissoc manifest :entries)
                                   (get-in manifest [:entries (artifact-id artifact)])
                                   {:artifact (artifact-id artifact)})))
            {:id id :handlers (count artifacts) :cached? (boolean existing)
             :compiler-invocations (if existing 0 1)
             :library-bytes (:library-bytes manifest) :debug-bytes (:debug-bytes manifest)}))))))

(defn- accept-pack-validation! [collected artifacts cached? started]
  (let [pending (filterv :validation-pending? artifacts)
        ids (mapv artifact-id pending)
        report {:candidates (count ids) :batch-limit validation-batch-limit
                :mode :compile-and-link
                :compiler-batches (if (and (seq ids) (not cached?)) 1 0)
                :individual-rechecks 0
                :statuses (if (seq ids) {:validated (count ids)} {})
                :duration-ms (/ (- (System/nanoTime) started) 1e6)}]
    (swap! collected
           (fn [state]
             (let [waiting (into #{} (keep #(when (:validation-pending? %)
                                              (artifact-id %)))
                                 (vals (:artifacts state)))]
               (when-not (= waiting (set ids))
                 (throw (ex-info "Bundle validation inputs changed during compilation" {})))
               (reduce (fn [state id]
                         (-> state
                             (assoc-in [:artifacts id :validation-pending?] false)
                             (assoc-in [:validation-results id] {:status :validated})))
                       (assoc state :validation report) ids))))
    (explain/event! (assoc report :event :batch-validation))))

(defn finish!
  "Compile all eligible handlers into one library, including cached handlers.
  Incompatible configurations fail explicitly instead of silently splitting the
  preparation into independent compilation images. No native code is invoked.
  :validate-pending? lets the full compiler build validate queued handlers before
  publication; otherwise every handler must already have passed validation."
  [cache-dir collected callbacks]
  (let [started (System/nanoTime)
        records (vals (:artifacts @collected))
        already (filter :bundle records)
        candidates (vec (keep candidate records))
        link-args (vec (last (sort-by count (map :link-args candidates))))
        ;; Namespace initialization can append native dependencies. Link the
        ;; full ordered list once; each handler keeps its original artifact key.
        extended-links? (every? #(= (vec (:link-args %))
                                    (subvec link-args 0 (count (:link-args %)))) candidates)
        compiler-groups (group-by #(vector (first (:command %))
                                           (:development-panic-support-path %)
                                           (:debug-format %) (:forwarder %)) candidates)
        groups (group-by #(vector (first (:command %))
                                  (:development-panic-support-path %)
                                  (:debug-format %) (:forwarder %)
                                  (:link-args %)
                                  (get-in % [:groups 0 :flags])) candidates)
        ;; All accepted :flags are Zig per-module options. materialize-entry!
        ;; and build-pack! preserve them before each module's -M, which resets
        ;; that option scope. Different optimization/safety modes therefore do
        ;; not require separate packs; global compiler/panic/debug/link inputs
        ;; still must agree, and each artifact key retains its original flags.
        _ (when (or (> (count compiler-groups) 1) (not extended-links?))
            (throw (ex-info "A single AOT bundle requires compatible compiler configurations"
                            {:aguafria/phase :bundle-compile
                             :reason :incompatible-bundle-configurations
                             :configurations (mapv (fn [[configuration artifacts]]
                                                     {:configuration configuration
                                                      :modules (mapv :module artifacts)})
                                                   groups)})))
        _ (when (and (some :validation-pending? records)
                     (not (:validate-pending? callbacks)))
            (throw (ex-info "Bundle publication requires validated artifacts"
                            {:aguafria/phase :bundle-compile :reason :pending-validation})))
        _ (when (and (:validate-pending? callbacks)
                     (not= (set (map artifact-id (filter :validation-pending? records)))
                           (set (map artifact-id (filter :validation-pending? candidates)))))
            (throw (ex-info "Queued validation requires bundle-eligible artifacts"
                            {:aguafria/phase :bundle-compile :reason :pending-validation})))
        callbacks (cond-> callbacks
                    (:validate-pending? callbacks)
                    (assoc :accept-validated!
                           #(accept-pack-validation! collected %1 %2 started)))
        packs (if (seq candidates)
                [(build-pack! cache-dir candidates link-args callbacks)]
                [])]
    (when (and (:validate-pending? callbacks) (empty? candidates))
      (accept-pack-validation! collected [] true started))
    {:packs packs :packed-handlers (reduce + 0 (map :handlers packs))
     :compiler-invocations (reduce + 0 (map :compiler-invocations packs))
     :validation (:validation @collected)
     :reused-handlers (count already)
     :standalone (mapv (fn [[id exclusion]]
                         (assoc exclusion :artifact-id id))
                       (sort-by key (:excluded @collected)))}))

(defn symbol-lookup
  "Open a bundle once per JVM. Keep its arena alive across individual handler
  generation retirement; all exported pointers/cleaners may outlive a lookup."
  [entry]
  (let [path (:library entry)
        image (locking (lock-for path)
                (or (get @images path)
                    (let [arena (Arena/ofShared)]
                      (try
                        (let [lookup (SymbolLookup/libraryLookup (.toPath (io/file path)) arena)
                              image {:lookup lookup :arena arena}]
                          (retain-loaded-artifacts! entry)
                          (swap! images assoc path image)
                          (explain/event! {:event :bundle-loaded :path path})
                          image)
                        (catch Throwable error (.close arena) (throw error))))))
        delegate ^SymbolLookup (:lookup image)
        exports (set (:exports entry))]
    (reify SymbolLookup
      (find [_ name]
        (.find delegate (if (exports name) (str (:prefix entry) name) name))))))
