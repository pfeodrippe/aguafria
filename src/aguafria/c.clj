(ns aguafria.c
  "Generate inspectable Aguafria namespaces from C headers through Zig.

  `translate-header!` runs the configured Zig compiler's `translate-c`, then
  feeds the resulting ordinary Zig module to Aguafria's structural converter.
  Generated namespaces contain the same documented, inspectable Vars as a
  hand-written or Zig-converted Aguafria module; no JVM-specific wrapper is
  introduced into standalone output."
  (:require [aguafria.zig.convert :as convert]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import (java.io File)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files StandardCopyOption StandardOpenOption)
           (java.security MessageDigest)
           (java.util HexFormat)))

(defonce ^:private generation-history (atom []))

(def ^:private project-catalog-name "aguafria-project.edn")

(defn- canonical-file
  [path description]
  (let [file (.getCanonicalFile (io/file path))]
    (when-not (.isFile file)
      (throw (ex-info (str description " is not a regular file")
                      {:path (str path) :resolved (.getAbsolutePath file)})))
    file))

(defn- canonical-directory
  [path]
  (let [file (.getCanonicalFile (io/file path))]
    (when-not (.isDirectory file)
      (throw (ex-info "C include path is not a directory"
                      {:path (str path) :resolved (.getAbsolutePath file)})))
    file))

(defn- sha256
  [value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes (pr-str value) StandardCharsets/UTF_8))
    (.formatHex (HexFormat/of) (.digest digest))))

(defn- run-command
  [command directory]
  (let [started (System/nanoTime)
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.directory directory))
        process (.start builder)
        stdout (atom nil)
        stderr (atom nil)
        ;; Do not use Clojure futures here. Aguafria's asynchronous compiler
        ;; also uses agent pools, so a saturated REPL could starve both pipe
        ;; readers and deadlock a subprocess after its OS buffer fills.
        stdout-thread (doto (Thread. ^Runnable
                                     #(reset! stdout
                                              (slurp (.getInputStream process)))
                                     "aguafria-c-binding-stdout")
                        (.setDaemon true)
                        (.start))
        stderr-thread (doto (Thread. ^Runnable
                                     #(reset! stderr
                                              (slurp (.getErrorStream process)))
                                     "aguafria-c-binding-stderr")
                        (.setDaemon true)
                        (.start))
        exit (.waitFor process)
        _ (.join stdout-thread)
        _ (.join stderr-thread)]
    {:command (vec command)
     :directory (.getAbsolutePath ^File directory)
     :exit exit
     :stdout (or @stdout "")
     :stderr (or @stderr "")
     :duration-ms (/ (- (System/nanoTime) started) 1e6)}))

(defn- zig-version
  [zig directory]
  (let [result (run-command [zig "version"] directory)]
    (if (zero? (:exit result))
      (str/trim (:stdout result))
      (throw (ex-info "Unable to run Zig for C binding generation"
                      (assoc result :aguafria/phase :c-binding-zig-version))))))

(defn- run-command-to-file
  "Run a compiler with stdout connected directly to a regular file.

  Zig 0.16 `translate-c` is dramatically slower when its generated source is
  drained through a pipe, even for small headers. A direct file descriptor is
  both bounded-memory and equivalent to the command-line redirection Zig's
  own documentation demonstrates."
  [command directory ^File output]
  (let [started (System/nanoTime)
        _ (io/make-parents output)
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.directory directory)
                  (.redirectOutput output))
        process (.start builder)
        stderr (atom nil)
        stderr-thread (doto (Thread. ^Runnable
                                     #(reset! stderr
                                              (slurp (.getErrorStream process)))
                                     "aguafria-c-binding-stderr")
                        (.setDaemon true)
                        (.start))
        exit (.waitFor process)
        _ (.join stderr-thread)]
    {:command (vec command)
     :directory (.getAbsolutePath ^File directory)
     :output-path (.getAbsolutePath output)
     :exit exit
     :stdout ""
     :stderr (or @stderr "")
     :duration-ms (/ (- (System/nanoTime) started) 1e6)}))

(def ^:private quoted-include-pattern
  #"(?m)^\s*#\s*include\s*\"([^\"]+)\"")

(defn- resolve-quoted-include
  [^File including include-directories include-name]
  (some (fn [^File directory]
          (let [candidate (.getCanonicalFile (io/file directory include-name))]
            (when (.isFile candidate) candidate)))
        (cons (.getParentFile including) include-directories)))

(defn- header-inputs
  "Return the local quoted-include closure used for cache invalidation.
  Platform/system headers remain represented by the Zig version and target."
  [^File header include-directories]
  (loop [pending [header]
         seen #{}
         result []]
    (if-let [^File file (first pending)]
      (let [path (.getAbsolutePath file)]
        (if (contains? seen path)
          (recur (next pending) seen result)
          (let [source (slurp file)
                includes (->> (re-seq quoted-include-pattern source)
                              (keep (fn [[_ include-name]]
                                      (resolve-quoted-include
                                       file include-directories include-name))))]
            (recur (concat (next pending) includes)
                   (conj seen path)
                   (conj result {:path path :source source})))))
      (sort-by :path result))))

(defn- definition-arguments
  [definitions]
  (cond
    (nil? definitions) []
    (map? definitions)
    (mapv (fn [[name value]]
            (if (or (nil? value) (true? value))
              (str "-D" (clojure.core/name name))
              (str "-D" (clojure.core/name name) "=" value)))
          (sort-by (comp str key) definitions))
    (sequential? definitions)
    (mapv #(str "-D" (clojure.core/name %)) definitions)
    :else
    (throw (ex-info ":defines must be a map or sequence"
                    {:defines definitions}))))

(defn- validate-options
  [{:keys [namespace include-dirs defines args zig cache-dir target cpu]}]
  (when-not namespace
    (throw (ex-info "C binding generation requires :namespace" {})))
  (when-not (every? #(or (string? %) (instance? File %)) include-dirs)
    (throw (ex-info ":include-dirs must contain filesystem paths"
                    {:include-dirs include-dirs})))
  (when-not (every? string? args)
    (throw (ex-info ":args must contain command-line strings" {:args args})))
  (when-not (every? #(or (nil? %) (string? %)) [zig cache-dir target cpu])
    (throw (ex-info "Zig, cache, target, and CPU options must be strings"
                    {:zig zig :cache-dir cache-dir :target target :cpu cpu})))
  (definition-arguments defines)
  true)

(def ^:private c-doc-declaration-pattern
  #"(?s)(/\*\*(?:(?!/\*\*).)*?\*/|(?:///[^\r\n]*(?:\r?\n|$))+)[\s\r\n]*(?:typedef\s+(?:struct|union|enum)\s+([A-Za-z_][A-Za-z0-9_]*)|[^;/{}]*?\b([A-Za-z_][A-Za-z0-9_]*)\s*\()")

(defn- normalize-c-doc
  [comment]
  (->> (str/split-lines comment)
       (map #(-> %
                 str/trim
                 (str/replace-first #"^/\*\*+\s*" "")
                 (str/replace-first #"^///\s?" "")
                 (str/replace-first #"^\*\s?" "")
                 (str/replace-first #"^/$" "")
                 (str/replace-first #"\s*\*/$" "")))
       (drop-while str/blank?)
       reverse
       (drop-while str/blank?)
       reverse
       (str/join "\n")))

(defn- c-documentation
  [inputs]
  (into {}
        (mapcat
         (fn [{:keys [source]}]
           (map (fn [[_ comment type-name function-name]]
                  [(or function-name type-name) (normalize-c-doc comment)])
                (re-seq c-doc-declaration-pattern source))))
        inputs))

(def ^:private translated-bool-identifier-pattern
  #"\bzig_(true|false)_[A-Za-z0-9_]+\b")

(defn- normalize-translated-c!
  "Repair reader-safe boolean macro identifiers emitted by Zig 0.16.

  Some system-header combinations make translate-c reference deterministic
  zig_true/zig_false identifiers without emitting their declarations.
  Materialize those declarations explicitly before structural conversion so
  the resulting Aguafria namespace contains no magical/unbound symbol."
  [^File translated-file]
  (let [source (slurp translated-file)
        identifiers (->> (re-seq translated-bool-identifier-pattern source)
                         (map first)
                         distinct
                         sort)
        missing (remove #(str/includes? source (str "pub const " % " ="))
                        identifiers)]
    (when (seq missing)
      (let [prefix (->> missing
                        (map (fn [identifier]
                               (str "pub const " identifier " = "
                                    (if (str/starts-with? identifier "zig_true_")
                                      "true" "false")
                                    ";\n")))
                        (apply str))
            normalized (str prefix "\n" source)]
        (Files/writeString
         (.toPath translated-file) normalized StandardCharsets/UTF_8
         (into-array StandardOpenOption
                     [StandardOpenOption/CREATE
                      StandardOpenOption/TRUNCATE_EXISTING
                      StandardOpenOption/WRITE]))))
    {:translated-bool-declarations (vec missing)}))

(defn- rust-style-translation-error
  [^File header result]
  (str "error[aguafria::translate-c]: Zig could not translate the C header\n"
       " --> " (.getAbsolutePath header) "\n"
       "  |\n"
       "  = command: " (str/join " " (:command result)) "\n"
       (when-not (str/blank? (:stderr result))
         (str "\n" (:stderr result)))))

(defn- namespace-output-root
  [^File output namespace]
  (let [segments (str/split (str namespace) #"\.")
        root (nth (iterate #(.getParentFile ^File %) output)
                  (count segments)
                  nil)
        relative (str (str/join File/separator
                                (map #(str/replace % "-" "_") segments))
                      ".clj")]
    (when (and root
               (= output (.getCanonicalFile (io/file root relative))))
      root)))

(defn- write-project-catalog!
  [^File output namespace catalog-module]
  (when-let [root (namespace-output-root output namespace)]
    (let [catalog-file (io/file root project-catalog-name)
          existing
          (if (.isFile catalog-file)
            (edn/read-string (slurp catalog-file))
            {:schema-version 1 :modules {}})
          catalog (assoc-in existing [:modules (str namespace)] catalog-module)
          source (with-out-str (pprint/pprint catalog))]
      (Files/writeString
       (.toPath catalog-file) source StandardCharsets/UTF_8
       (into-array StandardOpenOption
                   [StandardOpenOption/CREATE
                    StandardOpenOption/TRUNCATE_EXISTING
                    StandardOpenOption/WRITE]))
      (.getAbsolutePath catalog-file))))

(defn translate-header!
  "Translate a C `header` into a well-formatted Aguafria namespace at `output`.

  Required option: `:namespace`. Supported options include `:zig`,
  `:cache-dir`, `:include-dirs`, `:defines`, `:target`, `:cpu`, `:args`, and
  `:overwrite?`. Local quoted includes participate in the content cache key.
  Returns an inspectable, serializable generation report."
  ([header output options]
   (validate-options options)
   (let [started (System/nanoTime)
         header (canonical-file header "C binding input")
         output (.getCanonicalFile (io/file output))
         zig (or (:zig options) "zig")
         cache-dir (.getCanonicalFile
                    (io/file (or (:cache-dir options) ".aguafria/c-bindings")))
         include-directories (mapv canonical-directory (:include-dirs options []))
         version (zig-version zig (.getParentFile header))
         inputs (header-inputs header include-directories)
         declaration-docs (c-documentation inputs)
         identity-data {:schema-version 1
                        :zig-version version
                        :namespace (str (:namespace options))
                        :inputs inputs
                        :include-dirs (mapv #(.getAbsolutePath ^File %)
                                            include-directories)
                        :defines (:defines options)
                        :target (:target options)
                        :cpu (:cpu options)
                        :args (:args options)}
         cache-key (sha256 identity-data)
         generation-dir (io/file cache-dir cache-key)
         translated-file (io/file generation-dir "translated.zig")
         partial-file (io/file generation-dir "translated.partial.zig")
         cache-hit? (.isFile translated-file)
         command (vec
                  (concat [zig "translate-c" "--color" "off"]
                          (when-let [target (:target options)] ["-target" target])
                          (when-let [cpu (:cpu options)] ["-mcpu" cpu])
                          (map #(str "-I" (.getAbsolutePath ^File %))
                               include-directories)
                          (definition-arguments (:defines options))
                          (:args options)
                          [(.getAbsolutePath header)]))
         translation (when-not cache-hit?
                       (run-command-to-file command (.getParentFile header)
                                            partial-file))
         _ (when (and translation (not (zero? (:exit translation))))
             (throw (ex-info (rust-style-translation-error header translation)
                             (assoc translation
                                    :aguafria/phase :c-binding-translate
                                    :header (.getAbsolutePath header)))))
         _ (when translation
             (Files/move (.toPath partial-file) (.toPath translated-file)
                         (into-array StandardCopyOption
                                     [StandardCopyOption/REPLACE_EXISTING])))
         normalization (normalize-translated-c! translated-file)
         conversion
         (convert/convert-file!
          translated-file output
          {:namespace (:namespace options)
           :source-display-path (.getAbsolutePath header)
           :declaration-docs declaration-docs
           :zig zig
           :cache-dir (.getAbsolutePath (io/file cache-dir "zig-conversion"))
           :overwrite? (boolean (:overwrite? options))})
         catalog-path
         (write-project-catalog! output (:namespace options)
                                 (:catalog-module conversion))
         report
         (merge
          {:header (.getAbsolutePath header)
           :output-path (.getAbsolutePath output)
           :translated-zig-path (.getAbsolutePath translated-file)
           :namespace (symbol (str (:namespace options)))
           :zig-version version
           :cache-key cache-key
           :cache-hit? cache-hit?
           :input-count (count inputs)
           :inputs (mapv :path inputs)
           :command command
           :translation-duration-ms (or (:duration-ms translation) 0.0)
           :normalization normalization
           :catalog-path catalog-path
           :elapsed-ms (/ (- (System/nanoTime) started) 1e6)}
          (select-keys conversion
                       [:written? :declaration-count
                        :structural-declaration-count :fallback-count
                        :unresolved-syntax-count]))]
     (swap! generation-history
            (fn [history]
              (->> (conj history (assoc report :finished-at-ms
                                        (System/currentTimeMillis)))
                   (take-last 100)
                   vec)))
     report)))

(defn load-bindings!
  "Load generated C bindings as ordinary Aguafria Vars.
  By default this registers source without compiling until used."
  ([path] (load-bindings! path {}))
  ([path options]
   (convert/load-converted! path options)))

(defn namespace-info
  "Return documented, inspectable public binding Vars for `namespace`.
  The namespace must already be required or loaded."
  [namespace]
  (let [namespace (or (find-ns (symbol (str namespace)))
                      (throw (ex-info "C binding namespace is not loaded"
                                      {:namespace (str namespace)})))]
    {:namespace (ns-name namespace)
     :count (count (ns-publics namespace))
     :bindings
     (->> (ns-publics namespace)
          (map (fn [[name var]]
                 (let [metadata (meta var)
                       declaration (:aguafria/declaration metadata)]
                   {:name name
                    :qualified-name (symbol (str (ns-name namespace)) (str name))
                    :doc (:doc metadata)
                    :arglists (:arglists metadata)
                    :zig-name (or (:zig-name declaration) (:name declaration))
                    :kind (:kind declaration)
                    :type (:type declaration)
                    :return (:return declaration)
                    :args (:args declaration)
                    :source (:source declaration)})))
          (sort-by (comp str :name))
          vec)}))

(defn stats
  "Return recent C-binding generation reports."
  []
  {:generation-count (count @generation-history)
   :generations @generation-history})
