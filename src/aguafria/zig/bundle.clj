(ns aguafria.zig.bundle
  "One immutable AOT library per preparation, indexed by ordinary artifact keys."
  (:require [aguafria.zig.artifact :as artifact]
            [aguafria.zig.explain :as explain]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.lang.foreign Arena SymbolLookup]
           [java.nio.channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption StandardOpenOption]
           [java.util UUID]))

(def ^:private version 2)
(def ^:dynamic *preparing* nil)
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
                                             (every? string? exports))
                                    [key (merge common item {:artifact key})])))
                          (:entries manifest))]
        ;; Existing loaded exports stay usable even if a later preparation
        ;; points the disk index at a different, overlapping pack.
        (swap! loaded-artifacts update (:cache-root entry)
               #(merge entries %))))))

;; This lexer is only for emitted ABI identifier isolation, never type inference.
;; Strings, character literals, quoted identifiers, multiline strings and comments
;; remain verbatim, including any text that resembles an exported ABI identifier.
(def ^:private zig-token
  #"(?s)//[^\n]*|@\"(?:\\.|[^\"\\])*\"|\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|\\\\[^\n]*|[A-Za-z_][A-Za-z_0-9]*|\s+|.")

(defn- tokens [source] (re-seq zig-token source))

(defn- analyze-source [source]
  (let [significant (into [] (remove #(or (str/blank? %) (str/starts-with? % "//")))
                          (tokens source))
        declarations (filterv #(= "export" (first %)) (partition 3 1 significant))]
    {:external-exports? (boolean
                         (some #(or (not= "fn" (second %))
                                    (not (re-matches #"__aguafria_[A-Za-z_0-9]+" (nth % 2))))
                               declarations))
     :dynamic-exports? (boolean (some #(= ["@" "export"] (vec %))
                                      (partition 2 1 significant)))
     :external-declaration-syntax? (boolean (some #{"extern"} significant))
     :exports (mapv #(nth % 2) declarations)
     :imports (into []
                    (keep (fn [[at builtin open argument close after]]
                            (when (and (= "@" at) (= "(" open)
                                       (#{"embedFile" "import"} builtin))
                              [builtin (when (or (= ")" close)
                                                 (and (= "," close) (= ")" after)))
                                         argument)])))
                    (partition-all 6 1 significant))}))

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
   (let [analysis (analyze-source source)
         asset-import? (some (fn [[kind argument]]
                               (or (= "embedFile" kind) (nil? argument)
                                   (and argument
                                        (or (str/includes? argument "/")
                                            (str/ends-with? argument ".zig\"")))))
                             (:imports analysis))
         masked-source (when (or asset-import? (:external-declaration-syntax? analysis))
                         (mask-linked-support source linked-support-fragments))
         application-extern? (and (:external-declaration-syntax? analysis)
                                  (some #{"extern"} (tokens masked-source)))
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
             (assoc (analyze-source (String. bytes StandardCharsets/UTF_8))
                    :external-declarations? external-declarations?))))))))

(defn- source-analysis [source key linked-support]
  ;; Keep only small lexical facts, not source or token vectors. The lifetime
  ;; is one preparation; content changes select a different key.
  (let [key [key (artifact/key-for :bundle-linked-support linked-support)]]
    (if-not *preparing*
      (library-source-analysis source (:fragments linked-support))
      (locking *preparing*
        (or (get-in @*preparing* [:source-analyses key])
            (let [analysis (library-source-analysis source (:fragments linked-support))]
              (swap! *preparing* assoc-in [:source-analyses key] analysis)
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
  (apply str (map #(get renames % %) (tokens source))))

(defn- bind-root-imports [source module-name]
  (loop [remaining (seq (tokens source)), previous [], output (transient [])]
    (if-let [token (first remaining)]
      (let [significant? (not (or (str/blank? token) (str/starts-with? token "//")))
            replacement (if (and (= ["@" "import" "("] previous) (= "\"root\"" token))
                          (artifact/print-data module-name)
                          token)]
        (recur (next remaining)
               (if significant? (vec (take-last 3 (conj previous token))) previous)
               (conj! output replacement)))
      (apply str (persistent! output)))))

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
  "Describe a packable emitted graph; unsupported configurations stay standalone."
  [artifact]
  (when (or (:jvm-adapter? artifact) (:jvm-wrapper? artifact))
    (try
      (when (or (not= :shared (:development-panic artifact)) (:native-test-context? artifact))
        (throw (ex-info "Handler requires standalone linking"
                        {:reason (if (:native-test-context? artifact) :native-test-context :panic-profile)})))
      (let [{:keys [groups link-args]} (module-groups artifact)
            linked-support (linked-compiler-support artifact)
            groups (mapv (fn [group]
                           (let [source (slurp (:path group))
                                 key (artifact/key-for :bundle-source source)
                                 analysis (source-analysis source key linked-support)]
                             (assoc group :source source :source-key key
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
        (when (seq names)
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
    (swap! *preparing* assoc-in [:artifacts (artifact-id artifact)] artifact))
  artifact)

(defn- materialize-entry! [directory artifact]
  (let [id (artifact-id artifact)
        prefix (str "pack_" id "_")
        groups (:groups artifact)
        names (into {} (map-indexed #(vector (:name %2) (str "m_" id "_" %1)) groups))
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
                         path (io/file directory (names name) (.getName (io/file (:path group))))]
                     (io/make-parents path)
                     (spit path (cond-> (rename-identifiers source renames)
                                  implicit-root? (bind-root-imports (names "root"))))
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

(defn- build-pack! [cache-dir artifacts link-args {:keys [run-command preserve-debug!]}]
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
              entries (when-not existing (mapv #(materialize-entry! directory %) artifacts))]
          (when existing
            (when-not (and (= (:library-bytes existing) (.length library))
                           (or (not debug?) (= (:debug-bytes existing) (.length debug))))
              (throw (ex-info "Existing immutable bundle is damaged; use a fresh cache directory"
                              {:bundle id :path (str directory)}))))
          (when-not existing
            (let [source (io/file directory "bundle.zig")
                  temporary (io/file directory (str "." (UUID/randomUUID) "-" (.getName library)))
                  command (concat [(first (:command first-artifact)) "build-lib" "-dynamic"
                                   (str "-femit-bin=" temporary)
                                   (:development-panic-support-path first-artifact)]
                                  link-args
                                  (get-in entries [0 :groups 0 :flags])
                                  (mapcat #(vector "--dep" (:entry %)) entries)
                                  [(str "-Mroot=" source)]
                                  (mapcat (fn [{:keys [flags new-deps new-name new-path]}]
                                            (concat flags (mapcat #(vector "--dep" %) new-deps)
                                                    [(str "-M" new-name "=" new-path)]))
                                          (mapcat :groups entries)))]
              (spit source (str (:forwarder first-artifact) "\ncomptime {\n"
                                (apply str (map #(str "    _ = @import(\"" (:entry %) "\");\n") entries))
                                "}\n"))
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
            (when-not existing (publish-edn! manifest-file manifest))
            ;; Publish pointers last. Concurrent readers see either a complete
            ;; old pack or a complete new pack, never a half-linked library.
            (doseq [artifact artifacts]
              (publish-edn! (index-file cache-dir artifact)
                            (merge (dissoc manifest :entries)
                                   (get-in manifest [:entries (artifact-id artifact)])
                                   {:artifact (artifact-id artifact)})))
            {:id id :handlers (count artifacts) :cached? (boolean existing)
             :library-bytes (:library-bytes manifest) :debug-bytes (:debug-bytes manifest)}))))))

(defn finish!
  "Compile all eligible handlers into one library, including cached handlers.
  Incompatible configurations fail explicitly instead of silently splitting the
  preparation into independent compilation images. No native code is invoked."
  [cache-dir collected callbacks]
  (let [records (vals (:artifacts @collected))
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
        packs (if (seq candidates)
                [(build-pack! cache-dir candidates link-args callbacks)]
                [])]
    {:packs packs :packed-handlers (reduce + 0 (map :handlers packs))
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
