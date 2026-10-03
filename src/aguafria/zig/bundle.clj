(ns aguafria.zig.bundle
  "One immutable AOT library per preparation, indexed by ordinary artifact keys."
  (:require [aguafria.zig.artifact :as artifact]
            [aguafria.zig.explain :as explain]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.lang.foreign Arena SymbolLookup]
           [java.nio.channels FileChannel]
           [java.nio.file Files StandardCopyOption StandardOpenOption]
           [java.util UUID]))

(def ^:private version 2)
(def ^:dynamic *preparing* nil)
(defonce ^:private locks (atom {}))
(defonce ^:private images (atom {}))

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
      (spit temporary (pr-str value))
      (Files/move (.toPath temporary) (.toPath (io/file path))
                  (into-array java.nio.file.CopyOption
                              [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
      (finally (Files/deleteIfExists (.toPath temporary))))))

(defn- artifact-id [{:keys [module hash]}]
  (artifact/key-for :bundle-entry [module hash]))

(defn- index-file [cache-dir artifact]
  (io/file cache-dir "bundles" "index" (str (artifact-id artifact) ".edn")))

(defn find-artifact
  "One direct index lookup. Missing/incomplete packs are cache misses, not scans."
  [cache-dir artifact]
  (let [{:keys [id prefix exports library-bytes debug-bytes] :as entry}
        (read-edn (index-file cache-dir artifact))]
    (when (and (= version (:version entry))
               (= (artifact-id artifact) (:artifact entry))
               (string? id) (re-matches #"[a-f0-9]{64}" id)
               (string? prefix) (vector? exports) (every? string? exports))
      (let [directory (io/file cache-dir "bundles" id)
            library (io/file directory (System/mapLibraryName "aguafria_bundle"))
            debug (io/file (str library ".dwarf"))]
        (when (and (file? library) (= library-bytes (.length library))
                   (or (nil? debug-bytes) (and (file? debug) (= debug-bytes (.length debug)))))
          (assoc entry :library (.getAbsolutePath library)
                 :debug (when debug-bytes (.getAbsolutePath debug))))))))

;; This lexer is only for emitted ABI identifier isolation, never type inference.
;; Strings, character literals, quoted identifiers, multiline strings and comments
;; remain verbatim, including any text that resembles an exported ABI identifier.
(def ^:private zig-token
  #"(?s)//[^\n]*|@\"(?:\\.|[^\"\\])*\"|\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|\\\\[^\n]*|[A-Za-z_][A-Za-z_0-9]*|\s+|.")

(defn- tokens [source] (re-seq zig-token source))

(defn- exported-names [source]
  (let [significant (remove #(or (str/blank? %) (str/starts-with? % "//")) (tokens source))
        declarations (filter #(= "export" (first %)) (partition 3 1 significant))]
    (when (some #(or (not= "fn" (second %))
                     (not (re-matches #"__aguafria_[A-Za-z_0-9]+" (nth % 2)))) declarations)
      (throw (ex-info "Bundle requires compiler-owned function exports" {:reason :external-exports})))
    (when (some #(= ["@" "export"] (vec %)) (partition 2 1 significant))
      (throw (ex-info "Dynamic exports require standalone linking" {:reason :dynamic-exports})))
    (mapv #(nth % 2) declarations)))

(defn- rename-identifiers [source renames]
  (apply str (map #(get renames % %) (tokens source))))

(defn- validate-imports! [{:keys [source deps]}]
  (let [allowed (into #{"std" "builtin"}
                      (map #(first (str/split % #"=" 2))) deps)
        significant (remove #(or (str/blank? %) (str/starts-with? % "//")) (tokens source))]
    (doseq [[at builtin open argument] (partition 4 1 significant)
            :when (and (= "@" at) (= "(" open) (#{"embedFile" "import"} builtin))]
      (when-not (and (= "import" builtin)
                     (some #(= argument (pr-str %)) allowed))
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

        (or (#{"-ODebug" "-Osafe" "-Ofast" "-Osmall"
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
  (when (str/starts-with? (:module artifact) "aguafria.jvm.")
    (try
      (when (or (not= :shared (:development-panic artifact)) (:native-test-context? artifact))
        (throw (ex-info "Handler requires standalone linking"
                        {:reason (if (:native-test-context? artifact) :native-test-context :panic-profile)})))
      (let [{:keys [groups link-args]} (module-groups artifact)
            groups (mapv #(assoc % :source (slurp (:path %))) groups)
            _ (doseq [group groups] (validate-imports! group))
            names (->> groups (mapcat #(exported-names (:source %))) distinct sort vec)
            forwarder (first (str/split (:source (first groups))
                                        #"// Aguafria development loader\." 2))]
        (when (seq names)
          (assoc artifact :groups groups :link-args link-args :exports names :forwarder forwarder)))
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
           (mapv (fn [{:keys [name deps source] :as group}]
                   ;; Zig type names include the file basename. Preserve it,
                   ;; rather than leaking bundle IDs into reflected type names.
                   (let [path (io/file directory (names name) (.getName (io/file (:path group))))]
                     (io/make-parents path)
                     (spit path (rename-identifiers source renames))
                     (assoc group :new-name (names name) :new-path (.getAbsolutePath path)
                            :new-deps
                            (mapv (fn [dependency]
                                    (let [[alias target] (str/split dependency #"=" 2)
                                          target (or target alias)]
                                      (when-not (names target)
                                        (throw (ex-info "Missing bundle module" {:dependency dependency})))
                                      (str alias "=" (names target)))) deps)))) groups))))

(defn- response-argument [argument]
  ;; Zig 0.16's response-file reader uses Args.IteratorGeneral, not a shell.
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

(defn- build-pack! [cache-dir artifacts {:keys [run-command preserve-debug!]}]
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
                                  (:link-args first-artifact)
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
        groups (group-by #(vector (first (:command %))
                                        (:development-panic-support-path %)
                                        (:debug-format %) (:forwarder %)
                                        (:link-args %)
                                        (get-in % [:groups 0 :flags])) candidates)
        _ (when (> (count groups) 1)
            (throw (ex-info "A single AOT bundle requires compatible compiler configurations"
                            {:aguafria/phase :bundle-compile
                             :reason :incompatible-bundle-configurations
                             :configurations (mapv (fn [[configuration artifacts]]
                                                     {:configuration configuration
                                                      :modules (mapv :module artifacts)})
                                                   groups)})))
        packs (if (seq candidates)
                [(build-pack! cache-dir candidates callbacks)]
                [])]
    {:packs packs :packed-handlers (reduce + 0 (map :handlers packs))
     :reused-handlers (count already)
     :standalone (vec (vals (:excluded @collected)))}))

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
                          (swap! images assoc path image)
                          (explain/event! {:event :bundle-loaded :path path})
                          image)
                        (catch Throwable error (.close arena) (throw error))))))
        delegate ^SymbolLookup (:lookup image)
        exports (set (:exports entry))]
    (reify SymbolLookup
      (find [_ name]
        (.find delegate (if (exports name) (str (:prefix entry) name) name))))))
