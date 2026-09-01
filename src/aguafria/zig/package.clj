(ns aguafria.zig.package
  "Pinned third-party Zig packages and EDN-backed API catalogs.

  Packages are ordinary Clojure data. Aguafria's embedded Zig fetches and
  verifies each archive, the converter discovers its public declarations, and
  `aguafria.pkg` interns those declarations as ordinary documented Vars.
  No dependency-specific Clojure source is generated."
  (:require [aguafria.zig.convert :as convert]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import [java.io BufferedInputStream File FileOutputStream InputStream
            PushbackReader StringWriter]
           [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption Files Path StandardCopyOption
            StandardOpenOption]
           [java.util Arrays UUID]
           [java.util.zip GZIPInputStream]))

(def ^:private fetch-project-build
  "const std = @import(\"std\");\n\npub fn build(b: *std.Build) void {\n    _ = b;\n}\n")

(def ^:private fetch-project-zon
  (str ".{\n"
       "    .name = .aguafria_package_fetch,\n"
       "    .version = \"0.0.0\",\n"
       "    .fingerprint = 0x74d76e368f28958e,\n"
       "    .minimum_zig_version = \"0.16.0\",\n"
       "    .dependencies = .{},\n"
       "    .paths = .{ \"build.zig\", \"build.zig.zon\" },\n"
       "}\n"))

(def ^:private package-lock (Object.))

(defonce ^:private installed-catalogs
  (atom []))

(def ^:private package-catalog-resource
  "aguafria/zig-packages.edn")

(def ^:private declaration-categories
  {"defn" :function
   "defn-" :function
   "fn-decl" :function
   "defextern" :function
   "fn-proto-decl" :function
   "defconst" :constant
   "const-decl" :constant
   "defvar" :variable
   "var-decl" :variable
   "defstruct" :type
   "struct-decl" :type
   "enum-field-decl" :enum-field
   "field-decl" :field
   "tuple-field-decl" :field})

(defn- write-string-if-changed!
  [^File file content]
  (when-let [parent (.getParentFile file)]
    (.mkdirs parent))
  (when-not (= content (when (.isFile file) (slurp file)))
    (Files/writeString
     (.toPath file) content StandardCharsets/UTF_8
     (into-array StandardOpenOption
                 [StandardOpenOption/CREATE
                  StandardOpenOption/TRUNCATE_EXISTING
                  StandardOpenOption/WRITE]))))

(defn- run-command
  [arguments directory]
  (let [builder (doto (ProcessBuilder. ^java.util.List (mapv str arguments))
                  (.directory (io/file directory))
                  (.redirectErrorStream true))
        process (.start builder)
        output (with-open [reader (io/reader (.getInputStream process))]
                 (slurp reader))
        exit (.waitFor process)]
    {:command (mapv str arguments)
     :directory (.getAbsolutePath (io/file directory))
     :exit exit
     :output output}))

(defn- package-directories
  []
  (let [cache-root (io/file (:cache-dir (runtime/configuration)) "packages")]
    {:root cache-root
     :fetch-project (io/file cache-root "fetch-project")
     :zig-cache (io/file cache-root "zig-cache")
     :sources (io/file cache-root "sources")}))

(defn- ensure-fetch-project!
  [^File directory]
  (.mkdirs directory)
  (write-string-if-changed! (io/file directory "build.zig")
                            fetch-project-build)
  (write-string-if-changed! (io/file directory "build.zig.zon")
                            fetch-project-zon)
  directory)

(defn- safe-package-name
  [package-name]
  (let [package-name (if (instance? clojure.lang.Named package-name)
                       (name package-name)
                       (str package-name))]
    (when-not (re-matches #"[A-Za-z_][A-Za-z0-9_-]*" package-name)
      (throw (ex-info "Zig package names must be simple module names"
                      {:aguafria/phase :zig-package-configuration
                       :package package-name})))
    package-name))

(defn- default-namespace-prefix
  [package-name]
  (symbol
   (str "aguafria.pkg."
        (-> package-name
            (str/replace "_" "-")
            (str/replace #"[^A-Za-z0-9.-]" "-")))))

(defn- zig-alias
  [package-name]
  (let [alias (str/replace package-name #"[^A-Za-z0-9_]" "_")]
    (if (re-matches #"[A-Za-z_][A-Za-z0-9_]*" alias)
      alias
      (str "package_" (Math/abs (long (hash package-name)))))))

(defn- validate-spec
  [package-name spec]
  (let [package-name (safe-package-name package-name)
        {:keys [url hash root dependencies zig-args namespace-prefix]} spec]
    (when-not (map? spec)
      (throw (ex-info "A Zig package specification must be a map"
                      {:aguafria/phase :zig-package-configuration
                       :package package-name
                       :value spec})))
    (when-not (and (string? url)
                   (re-matches #"https://[^\s]+(?:\.tar\.gz|\.tgz)" url))
      (throw (ex-info "A Zig package :url must name an HTTPS tar.gz archive"
                      {:aguafria/phase :zig-package-configuration
                       :package package-name
                       :url url})))
    (when-not (and (string? hash)
                   (re-matches #"[A-Za-z0-9_.-]+-[A-Za-z0-9_-]+" hash))
      (throw (ex-info "A Zig package requires its pinned Zig package :hash"
                      {:aguafria/phase :zig-package-configuration
                       :package package-name
                       :hash hash})))
    (when-not (and (string? root)
                   (not (str/blank? root))
                   (not (.isAbsolute (io/file root))))
      (throw (ex-info "A Zig package :root must be a relative source path"
                      {:aguafria/phase :zig-package-configuration
                       :package package-name
                       :root root})))
    (when-not (or (nil? dependencies)
                  (and (sequential? dependencies)
                       (every? #(or (string? %)
                                    (keyword? %)
                                    (symbol? %))
                               dependencies)))
      (throw (ex-info "Zig package :dependencies must be module names"
                      {:aguafria/phase :zig-package-configuration
                       :package package-name
                       :dependencies dependencies})))
    (when-not (or (nil? zig-args)
                  (and (sequential? zig-args) (every? string? zig-args)))
      (throw (ex-info "Zig package :zig-args must be strings"
                      {:aguafria/phase :zig-package-configuration
                       :package package-name
                       :zig-args zig-args})))
    (when-not (or (nil? namespace-prefix)
                  (symbol? namespace-prefix)
                  (string? namespace-prefix))
      (throw (ex-info "Zig package :namespace-prefix must be a namespace symbol"
                      {:aguafria/phase :zig-package-configuration
                       :package package-name
                       :namespace-prefix namespace-prefix})))
    [package-name (assoc spec
                         :url url
                         :hash hash
                         :root root
                         :namespace-prefix
                         (symbol (str (or namespace-prefix
                                          (default-namespace-prefix package-name))))
                         :zig-alias (zig-alias package-name)
                         :dependencies (mapv safe-package-name dependencies)
                         :zig-args (vec zig-args))]))

(defn- archive-file
  [^File zig-cache hash]
  (let [package-cache (io/file zig-cache "p")
        candidates (when (.isDirectory package-cache)
                     (->> (.listFiles package-cache)
                          (filter #(.isFile ^File %))
                          (filter #(or (= hash (.getName ^File %))
                                       (str/starts-with? (.getName ^File %)
                                                         (str hash "."))))
                          (sort-by #(.getName ^File %))))]
    (when (> (count candidates) 1)
      (throw (ex-info "The Zig package cache contains ambiguous archives"
                      {:aguafria/phase :zig-package-cache
                       :hash hash
                       :candidates (mapv #(.getAbsolutePath ^File %) candidates)})))
    (first candidates)))

(defn- fetch-archive!
  [url expected-hash]
  (let [{:keys [fetch-project zig-cache]} (package-directories)
        cached (archive-file zig-cache expected-hash)]
    (if cached
      {:archive cached :cached? true}
      (let [fetch-project (ensure-fetch-project! fetch-project)
            _ (.mkdirs zig-cache)
            result (run-command [(runtime/zig-executable)
                                 "fetch"
                                 "--global-cache-dir"
                                 (.getAbsolutePath zig-cache)
                                 url]
                                fetch-project)
            actual-hash (str/trim (:output result))]
        (when-not (zero? (:exit result))
          (throw (ex-info "Embedded Zig could not fetch the package"
                          {:aguafria/phase :zig-package-fetch
                           :url url
                           :expected-hash expected-hash
                           :command (:command result)
                           :exit (:exit result)
                           :output (:output result)})))
        (when-not (= expected-hash actual-hash)
          (throw (ex-info "Fetched Zig package does not match its pinned hash"
                          {:aguafria/phase :zig-package-hash
                           :url url
                           :expected-hash expected-hash
                           :actual-hash actual-hash})))
        (let [archive (archive-file zig-cache expected-hash)]
          (when-not archive
            (throw (ex-info "Embedded Zig fetched a package without a cached archive"
                            {:aguafria/phase :zig-package-cache
                             :url url
                             :hash expected-hash
                             :cache (.getAbsolutePath zig-cache)})))
          {:archive archive :cached? false})))))

(defn- read-exactly!
  [^InputStream input ^bytes bytes length]
  (loop [offset 0]
    (if (= offset length)
      true
      (let [read (.read input bytes offset (- length offset))]
        (if (neg? read)
          false
          (recur (+ offset read)))))))

(defn- zero-header?
  [^bytes header]
  (every? zero? header))

(defn- tar-string
  [^bytes header offset length]
  (let [end (loop [index offset]
              (if (or (= index (+ offset length))
                      (zero? (aget header index)))
                index
                (recur (inc index))))]
    (String. header offset (- end offset) StandardCharsets/UTF_8)))

(defn- tar-size
  [^bytes header]
  (let [value (-> (tar-string header 124 12)
                  (str/replace #"[\u0000 ]" "")
                  str/trim)]
    (if (str/blank? value) 0 (Long/parseLong value 8))))

(defn- skip-exactly!
  [^InputStream input length]
  (let [buffer (byte-array 8192)]
    (loop [remaining (long length)]
      (when (pos? remaining)
        (let [read (.read input buffer 0 (int (min remaining (alength buffer))))]
          (when (neg? read)
            (throw (ex-info "Truncated Zig package archive"
                            {:aguafria/phase :zig-package-extract
                             :remaining remaining})))
          (recur (- remaining read)))))))

(defn- copy-entry!
  [^InputStream input ^File output size]
  (when-let [parent (.getParentFile output)]
    (.mkdirs parent))
  (with-open [destination (FileOutputStream. output)]
    (let [buffer (byte-array 16384)]
      (loop [remaining (long size)]
        (when (pos? remaining)
          (let [read (.read input buffer 0 (int (min remaining (alength buffer))))]
            (when (neg? read)
              (throw (ex-info "Truncated Zig package archive entry"
                              {:aguafria/phase :zig-package-extract
                               :path (.getAbsolutePath output)
                               :remaining remaining})))
            (.write destination buffer 0 read)
            (recur (- remaining read))))))))

(defn- safe-entry-file
  [^File output-root entry-name]
  (let [root-path (.normalize (.toAbsolutePath (.toPath output-root)))
        output-path (.normalize (.resolve root-path entry-name))]
    (when-not (.startsWith output-path root-path)
      (throw (ex-info "Zig package archive entry escapes its cache directory"
                      {:aguafria/phase :zig-package-extract
                       :entry entry-name})))
    (.toFile output-path)))

(defn- extract-tar-gz!
  [^File archive ^File output-root]
  (.mkdirs output-root)
  (with-open [input (GZIPInputStream.
                     (BufferedInputStream. (io/input-stream archive)))]
    (let [header (byte-array 512)]
      (loop []
        (when (read-exactly! input header 512)
          (when-not (zero-header? header)
            (let [name (tar-string header 0 100)
                  prefix (tar-string header 345 155)
                  entry-name (if (str/blank? prefix) name (str prefix "/" name))
                  size (tar-size header)
                  type-flag (char (bit-and 0xff (aget header 156)))
                  output (safe-entry-file output-root entry-name)]
              (case type-flag
                \5 (do (.mkdirs output) (skip-exactly! input size))
                \0 (copy-entry! input output size)
                \u0000 (copy-entry! input output size)
                (skip-exactly! input size))
              (skip-exactly! input (mod (- 512 (mod size 512)) 512))
              (recur))))))))

(defn- delete-tree!
  [^File root]
  (when (.exists root)
    (with-open [paths (Files/walk (.toPath root) (make-array java.nio.file.FileVisitOption 0))]
      (doseq [^Path path (reverse (sort-by #(.getNameCount ^Path %) (iterator-seq (.iterator paths))))]
        (Files/deleteIfExists path)))))

(defn- package-root
  [^File extraction-root]
  (with-open [paths (Files/walk (.toPath extraction-root)
                                (make-array java.nio.file.FileVisitOption 0))]
    (let [manifests (->> (iterator-seq (.iterator paths))
                         (filter #(= "build.zig.zon"
                                     (str (.getFileName ^Path %))))
                         (sort-by #(.getNameCount ^Path %))
                         vec)]
      (when-not (= 1 (count manifests))
        (throw (ex-info "A Zig package archive must contain one build.zig.zon"
                        {:aguafria/phase :zig-package-extract
                         :directory (.getAbsolutePath extraction-root)
                         :manifests (mapv str manifests)})))
      (.toFile (.getParent ^Path (first manifests))))))

(defn- materialize-source!
  [hash ^File archive]
  (let [{:keys [sources]} (package-directories)
        output (io/file sources hash)
        marker (io/file output ".aguafria-package-root")]
    (.mkdirs sources)
    (if (.isFile marker)
      (io/file output (str/trim (slurp marker)))
      (let [temporary (io/file sources (str "." hash "-" (UUID/randomUUID)))]
        (try
          (extract-tar-gz! archive temporary)
          (let [root (package-root temporary)
                relative (str (.relativize (.toPath temporary) (.toPath root)))]
            (write-string-if-changed!
             (io/file temporary ".aguafria-package-root") relative)
            (delete-tree! output)
            (Files/move (.toPath temporary) (.toPath output)
                        (into-array CopyOption
                                    [StandardCopyOption/ATOMIC_MOVE]))
            (io/file output relative))
          (catch java.nio.file.AtomicMoveNotSupportedException _
            (Files/move (.toPath temporary) (.toPath output)
                        (into-array CopyOption
                                    [StandardCopyOption/REPLACE_EXISTING]))
            (io/file output
                     (str/trim (slurp (io/file output ".aguafria-package-root")))))
          (catch Throwable error
            (delete-tree! temporary)
            (throw error)))))))

(defn- resolve-root!
  [package-name {:keys [url hash root]}]
  (let [{:keys [archive cached?]} (fetch-archive! url hash)
        package-root (materialize-source! hash archive)
        canonical-package-root (.getCanonicalFile package-root)
        source (.getCanonicalFile (io/file canonical-package-root root))
        prefix (str (.getPath canonical-package-root) File/separator)]
    (when-not (or (= canonical-package-root source)
                  (str/starts-with? (.getPath source) prefix))
      (throw (ex-info "Zig package root source escapes the package"
                      {:aguafria/phase :zig-package-configuration
                       :package package-name
                       :root root})))
    (when-not (.isFile source)
      (throw (ex-info "Zig package root source does not exist"
                      {:aguafria/phase :zig-package-configuration
                       :package package-name
                       :root root
                       :resolved-path (.getAbsolutePath source)})))
    {:name package-name
     :url url
     :hash hash
     :package-root (.getAbsolutePath canonical-package-root)
     :root-path (.getAbsolutePath source)
     :cached? cached?}))

(defn- declaration-parts
  [form]
  (when (and (seq? form) (symbol? (first form)) (symbol? (second form)))
    (let [[operator declaration-name & tail] form
          [documentation tail] (if (string? (first tail))
                                 [(first tail) (next tail)]
                                 [nil tail])
          [attributes payload] (if (map? (first tail))
                                 [(first tail) (next tail)]
                                 [{} tail])]
      {:operator operator
       :name declaration-name
       :documentation (or documentation (:doc attributes))
       :attributes attributes
       :payload payload})))

(defn- public-declaration?
  [{:keys [attributes]}]
  (or (true? (:public attributes))
      (contains? (set (:attrs attributes)) :public)))

(defn- declaration-zig-name
  [{:keys [name attributes]}]
  (or (:zig/name attributes)
      (:zig/name (meta name))
      (str name)))

(defn- declaration-category
  [{:keys [operator name attributes payload]}]
  (let [operator-name (clojure.core/name operator)
        body (first payload)]
    (cond
      (:zig/import-name attributes) :namespace
      (= :constant (get declaration-categories operator-name))
      (if (or (= "type" (when (and (seq? body) (symbol? (first body)))
                          (clojure.core/name (first body))))
              (= "container" (when (and (seq? body) (symbol? (first body)))
                               (clojure.core/name (first body))))
              (re-matches #"[A-Z].*" (str name)))
        :type
        :constant)
      :else (get declaration-categories operator-name :declaration))))

(defn- function-param-count
  [{:keys [operator payload]}]
  (when (= :function (get declaration-categories (clojure.core/name operator)))
    (some->> payload (filter vector?) first count)))

(defn- byte-string
  [^bytes source start end]
  (String. (Arrays/copyOfRange source (int start) (int end))
           StandardCharsets/UTF_8))

(defn- declaration-signature
  [source]
  (let [length (count source)]
    (loop [index 0
           parens 0
           brackets 0
           quote nil
           escaped? false]
      (if (= index length)
        (str/trim (str/replace source #"\s+" " "))
        (let [character (.charAt ^String source index)]
          (cond
            escaped? (recur (inc index) parens brackets quote false)
            (and quote (= character \\))
            (recur (inc index) parens brackets quote true)
            quote
            (recur (inc index) parens brackets
                   (when-not (= character quote) quote) false)
            (or (= character \") (= character \'))
            (recur (inc index) parens brackets character false)
            (= character \() (recur (inc index) (inc parens) brackets nil false)
            (= character \)) (recur (inc index) (max 0 (dec parens)) brackets nil false)
            (= character \[) (recur (inc index) parens (inc brackets) nil false)
            (= character \]) (recur (inc index) parens (max 0 (dec brackets)) nil false)
            (and (zero? parens) (zero? brackets)
                 (or (= character \{) (= character \;)))
            (-> (subs source 0 index) (str/replace #"\s+" " ") str/trim)
            :else (recur (inc index) parens brackets nil false)))))))

(defn- source-signatures
  [parsed]
  (into {}
        (keep (fn [{:keys [zig-name start-byte end-byte]}]
                (when zig-name
                  [zig-name
                   (declaration-signature
                    (byte-string (:source-bytes parsed) start-byte end-byte))])))
        (convert/declaration-spans parsed)))

(defn- namespace-symbol
  [prefix clojure-path]
  (symbol (str prefix
               (when (seq clojure-path)
                 (str "." (str/join "." (map str clojure-path)))))))

(defn- member-entry
  [package-name zig-alias access-path source signatures declaration]
  (let [zig-name (declaration-zig-name declaration)
        category (declaration-category declaration)]
    (cond->
     (sorted-map
      :category category
      :clojure-name (str (:name declaration))
      :documentation (or (:documentation declaration) "")
      :package package-name
      :source source
      :zig-alias zig-alias
      :zig-name (str/join "." (conj (vec access-path) zig-name)))
      (get signatures zig-name) (assoc :signature (get signatures zig-name))
      (some? (function-param-count declaration))
      (assoc :param-count (function-param-count declaration))
      (= :type category) (assoc :type-reference? true))))

(defn- container-form
  [{:keys [payload]}]
  (some (fn [value]
          (when (and (seq? value)
                     (symbol? (first value))
                     (= "container" (clojure.core/name (first value))))
            value))
        payload))

(declare container-catalogs)

(defn- container-catalogs
  [package-name prefix zig-alias source access-path clojure-path declaration]
  (when-let [container (container-form declaration)]
    (let [options (second container)
          enum? (contains? (set (:attrs options)) :enum)
          declarations (keep declaration-parts (drop 2 container))
          visible (filter #(or (public-declaration? %)
                               (and enum?
                                    (= :enum-field
                                       (get declaration-categories
                                            (clojure.core/name (:operator %))))))
                          declarations)
          zig-container (declaration-zig-name declaration)
          container-access (conj (vec access-path) zig-container)
          container-clojure (conj (vec clojure-path) (str (:name declaration)))
          namespace-name (namespace-symbol prefix container-clojure)
          members (mapv #(assoc (member-entry package-name zig-alias
                                               container-access source {} %)
                                :symbol
                                (symbol (str namespace-name) (str (:name %))))
                        visible)
          nested (mapcat #(container-catalogs package-name prefix zig-alias source
                                              container-access container-clojure %)
                         visible)]
      (into (cond-> [] (seq members)
              (conj (sorted-map :members members
                                :name namespace-name
                                :zig-path (str/join "." container-access))))
            nested))))

(defn- relative-source
  [^File package-root ^File file]
  (-> (.toPath package-root)
      (.relativize (.toPath file))
      str
      (str/replace "\\" "/")))

(declare catalog-file)

(defn- catalog-file
  [package-name spec resolved file access-path clojure-path active-files]
  (let [file (.getCanonicalFile ^File file)
        canonical-path (.getAbsolutePath file)]
    (when-not (contains? active-files canonical-path)
      (let [parsed (convert/parse-file file)
            converted (convert/convert-file
                       file
                       {::convert/parsed parsed
                        :namespace
                        (symbol "aguafria.pkg.catalog"
                                (str "n" (Math/abs (long (hash [package-name
                                                                canonical-path])))))})
            declarations (keep declaration-parts (:forms converted))
            public (filter public-declaration? declarations)
            prefix (:namespace-prefix spec)
            namespace-name (namespace-symbol prefix clojure-path)
            source (relative-source (io/file (:package-root resolved)) file)
            signatures (source-signatures parsed)
            members (mapv #(assoc (member-entry package-name (:zig-alias spec)
                                                access-path source signatures %)
                                  :symbol
                                  (symbol (str namespace-name) (str (:name %))))
                          public)
            container-namespaces
            (mapcat #(container-catalogs package-name prefix (:zig-alias spec)
                                         source access-path clojure-path %)
                    public)
            children
            (mapcat
             (fn [declaration]
               (when-let [import-name (:zig/import-name (:attributes declaration))]
                 (let [child (.getCanonicalFile
                              (io/file (.getParentFile file) import-name))]
                   (when (.isFile child)
                     (catalog-file package-name spec resolved child
                                   (conj (vec access-path)
                                         (declaration-zig-name declaration))
                                   (conj (vec clojure-path)
                                         (str (:name declaration)))
                                   (conj active-files canonical-path))))))
             public)]
        (into [(sorted-map :members members
                           :name namespace-name
                           :source source
                           :zig-path (str/join "." access-path))]
              (concat container-namespaces children))))))

(defn- merge-catalog-namespaces
  [namespaces]
  (->> namespaces
       (group-by :name)
       (map (fn [[namespace-name entries]]
              (let [members (->> entries
                                 (mapcat :members)
                                 (reduce (fn [result member]
                                           (let [name (:clojure-name member)]
                                             (if-let [existing (get result name)]
                                               (if (= (:zig-name existing)
                                                      (:zig-name member))
                                                 result
                                                 (throw
                                                  (ex-info
                                                   "Zig package declarations collide as Clojure Vars"
                                                   {:aguafria/phase :zig-package-catalog
                                                    :namespace namespace-name
                                                    :first existing
                                                    :second member})))
                                               (assoc result name member))))
                                         (sorted-map))
                                 vals
                                 vec)]
                (sorted-map :members members
                            :name namespace-name
                            :source (:source (first entries))
                            :zig-path (:zig-path (first entries))))))
       (sort-by (comp str :name))
       vec))

(defn- flat-member-name
  [zig-name]
  (let [candidate (-> zig-name
                      (str/replace #"[^A-Za-z0-9_]+" "-")
                      (str/replace "_" "-")
                      (str/replace #"^-+|-+$" ""))
        candidate (if (str/blank? candidate) "member" candidate)
        candidate (if (re-matches #"[0-9].*" candidate)
                    (str "member-" candidate)
                    candidate)
        symbol (symbol candidate)]
    (if (or (contains? #{"nil" "true" "false"} candidate)
            (emitter/structural-operator? symbol))
      (str candidate "-zig")
      candidate)))

(defn- add-flat-root-members
  [prefix namespaces]
  (let [root (some #(when (= prefix (:name %)) %) namespaces)
        descendants (for [namespace namespaces
                          member (:members namespace)
                          :when (str/includes? (:zig-name member) ".")]
                      (assoc member
                             :clojure-name (flat-member-name (:zig-name member))
                             :symbol (symbol (str prefix)
                                             (flat-member-name (:zig-name member)))))
        members (concat (:members root) descendants)
        grouped (group-by :clojure-name members)
        collision (some (fn [[name entries]]
                          (when (> (count (distinct (map :zig-name entries))) 1)
                            [name entries]))
                        grouped)]
    (when collision
      (throw (ex-info "Flattened Zig package declarations collide"
                      {:aguafria/phase :zig-package-catalog
                       :namespace prefix
                       :clojure-name (first collision)
                       :members (second collision)})))
    (let [root (assoc (or root (sorted-map :name prefix :zig-path ""))
                      :members (->> grouped vals (map first)
                                    (sort-by :clojure-name) vec))]
      (->> namespaces
           (remove #(= prefix (:name %)))
           (cons root)
           (sort-by (comp str :name))
           vec))))

(defn- package-catalog
  [package-name spec resolved]
  (let [root (io/file (:root-path resolved))
        namespaces (merge-catalog-namespaces
                    (catalog-file package-name spec resolved root [] [] #{}))
        namespaces (add-flat-root-members (:namespace-prefix spec) namespaces)]
    {:namespaces namespaces
     :package package-name}))

(defn install!
  "Fetch, verify, cache, and configure named third-party Zig packages.

  `packages` maps each Zig import name to data with `:url`, Zig package
  `:hash`, and its relative `:root` source. Optional `:dependencies` names
  other installed modules visible to that package, while `:zig-args` adds
  arguments scoped to that module.

      (install!
       {\"uuid\" {:url  \"https://example.test/uuid-0.5.0.tar.gz\"
                  :hash \"uuid-0.5.0-...\"
                  :root \"src/main.zig\"}})

  Most users call `prepare!` once and require the EDN-cataloged Vars from an
  `aguafria.pkg.*` namespace. `install!` is the lower-level package resolver
  used by that catalog loader."
  [packages]
  (when-not (map? packages)
    (throw (ex-info "Zig packages must be a map keyed by import name"
                    {:aguafria/phase :zig-package-configuration
                     :value packages})))
  (locking package-lock
    (let [specs (into (sorted-map) (map (fn [[name spec]]
                                          (validate-spec name spec))) packages)
          resolved (into (sorted-map)
                         (map (fn [[name spec]]
                                [name (resolve-root! name spec)]))
                         specs)
          configuration (runtime/configuration)
          modules (merge (:modules configuration)
                         (into {} (map (fn [[name package]]
                                        [name (:root-path package)])) resolved))
          cache-tokens (merge (:module-cache-tokens configuration)
                              (into {} (map (fn [[name package]]
                                             [name (:hash package)])) resolved))
          module-dependencies
          (merge (:module-dependencies configuration)
                 (into {} (keep (fn [[name spec]]
                                  (when (seq (:dependencies spec))
                                    [name (:dependencies spec)]))) specs))
          module-zig-args
          (merge (:module-zig-args configuration)
                 (into {} (keep (fn [[name spec]]
                                  (when (seq (:zig-args spec))
                                    [name (:zig-args spec)]))) specs))]
      (runtime/configure! {:modules modules
                           :module-cache-tokens cache-tokens
                           :module-dependencies module-dependencies
                           :module-zig-args module-zig-args})
      resolved)))

(defn- read-edn
  [source]
  (with-open [reader (PushbackReader. (io/reader source))]
    (edn/read {:eof nil} reader)))

(defn- pprint-edn
  [value]
  (let [writer (StringWriter.)]
    (binding [*out* writer
              pprint/*print-right-margin* 100]
      (pprint/pprint value))
    (str writer)))

(defn prepare!
  "Fetch packages and generate their complete EDN-backed Clojure Var catalog.

  Intended for a tools.deps exec alias before starting the JVM:

      :prepare-packages
      {:exec-fn aguafria.zig.package/prepare!
       :exec-args {:config \"aguafria-packages.edn\"
                   :output \"resources/aguafria/zig-packages.edn\"}}

  The config is either the package map itself or `{:packages package-map}`.
  Public nested Zig modules are exposed both as nested `aguafria.pkg.*`
  namespaces and as unambiguous flattened Vars in the package root namespace."
  [{:keys [config output]
    :or {config "aguafria-packages.edn"
         output "resources/aguafria/zig-packages.edn"}}]
  (let [configuration (read-edn config)
        packages (or (:packages configuration) configuration)
        specs (into (sorted-map)
                    (map (fn [[name spec]] (validate-spec name spec)))
                    packages)
        resolved (install! specs)
        package-catalogs (mapv (fn [[name spec]]
                                 (package-catalog name spec (get resolved name)))
                               specs)
        namespaces (->> package-catalogs
                        (mapcat :namespaces)
                        merge-catalog-namespaces)
        catalog (sorted-map
                 :generated-by "aguafria.zig.package/prepare!"
                 :member-count (reduce + 0 (map (comp count :members) namespaces))
                 :namespaces namespaces
                 :packages specs
                 :schema-version 1
                 :zig-version (:zig-version (runtime/toolchain-information)))
        output-file (.getCanonicalFile (io/file output))]
    (write-string-if-changed! output-file (pprint-edn catalog))
    {:catalog (.getAbsolutePath output-file)
     :member-count (:member-count catalog)
     :namespace-count (count namespaces)
     :package-count (count specs)
     :packages (vec (keys specs))
     :zig-version (:zig-version catalog)}))

(defn- reference-form-builder
  [reference]
  (fn [& arguments]
    (with-meta (apply list (:symbol reference) arguments)
      {:aguafria/zig-reference reference})))

(defn- catalog-reference
  [member]
  {:category (:category member)
   :import (:package member)
   :kind :import-member
   :member (:zig-name member)
   :module (:zig-alias member)
   :symbol (:symbol member)
   :type-reference? (:type-reference? member)
   :zig-name (str (:zig-alias member) "." (:zig-name member))})

(defn- member-doc
  [{:keys [category documentation package signature source zig-name]}]
  (str (when (seq signature) (str signature "\n\n"))
       (when (seq documentation) (str documentation "\n\n"))
       "This Var represents Zig `" package "." zig-name "` ("
       (name category) ") from `" source "`. Inside an `az/defn` it emits "
       "the Zig reference directly. Calling it at the Clojure REPL returns "
       "inspectable Aguafria form data."))

(defn- install-member!
  [target-ns member]
  (let [sym (symbol (:clojure-name member))
        existing (.findInternedVar ^clojure.lang.Namespace target-ns sym)
        reference (catalog-reference member)]
    (when (and existing (not (:aguafria/package (meta existing))))
      (throw (ex-info "EDN-derived Zig package Var collides with an existing Var"
                      {:aguafria/phase :zig-package-catalog
                       :namespace (ns-name target-ns)
                       :symbol sym
                       :existing (meta existing)})))
    (when (and (nil? existing)
               (.getMapping ^clojure.lang.Namespace target-ns sym))
      (ns-unmap target-ns sym))
    (let [value (reference-form-builder reference)
          var (if existing
                (do (alter-var-root existing (constantly value)) existing)
                (intern target-ns sym value))]
      (alter-meta!
       var merge
       {:aguafria/package true
        :aguafria/zig-reference reference
        :arglists '([& arguments])
        :doc (member-doc member)
        :zig/category (:category member)
        :zig/documentation-source :zig-package
        :zig/name (:zig-name member)
        :zig/package (:package member)
        :zig/param-count (:param-count member)
        :zig/signature (:signature member)
        :zig/source (:source member)})
      var)))

(defn- loaded-libs-ref
  []
  (let [loaded-libs-var (ns-resolve 'clojure.core '*loaded-libs*)
        loaded-libs (when loaded-libs-var (var-get loaded-libs-var))]
    (when-not (instance? clojure.lang.Ref loaded-libs)
      (throw (ex-info "This Clojure runtime cannot register EDN-backed package namespaces"
                      {:clojure-version (clojure-version)
                       :actual (some-> loaded-libs type str)})))
    loaded-libs))

(defn- ensure-namespace!
  [namespace-name]
  (or (find-ns namespace-name)
      (let [target-ns (create-ns namespace-name)]
        (binding [*ns* target-ns]
          (clojure.core/refer 'clojure.core))
        target-ns)))

(defn install-catalog!
  "Configure and intern all package Vars from one generated catalog map.

  Var identities are preserved across repeated REPL loads. Namespace symbols
  are registered with Clojure's normal loader, so subsequent `:require`
  libspecs and editor completion behave like source-backed namespaces."
  [catalog]
  (when-not (and (map? catalog)
                 (= 1 (:schema-version catalog))
                 (map? (:packages catalog))
                 (sequential? (:namespaces catalog)))
    (throw (ex-info "Invalid Aguafria Zig package catalog"
                    {:aguafria/phase :zig-package-catalog
                     :expected-schema-version 1
                     :catalog (select-keys catalog [:schema-version])})))
  (locking package-lock
    (install! (:packages catalog))
    (let [namespace-names
          (mapv :name (:namespaces catalog))
          var-count
          (reduce
           + 0
           (map
            (fn [{:keys [name members]}]
              (let [target-ns (ensure-namespace! name)
                    expected (set (map (comp symbol :clojure-name) members))]
                (doseq [[sym var] (ns-interns target-ns)
                        :when (and (:aguafria/package (meta var))
                                   (not (contains? expected sym)))]
                  (ns-unmap target-ns sym))
                (count (mapv #(install-member! target-ns %) members))))
            (:namespaces catalog)))
          loaded-libs (loaded-libs-ref)]
      (dosync (alter loaded-libs into namespace-names))
      {:member-count var-count
       :namespace-count (count namespace-names)
       :package-count (count (:packages catalog))})))

(defn install-resource-catalogs!
  "Discover and install every `aguafria/zig-packages.edn` on the classpath."
  []
  (let [loader (.getContextClassLoader (Thread/currentThread))
        resources (vec (enumeration-seq
                        (.getResources loader package-catalog-resource)))]
    (when (empty? resources)
      (throw (ex-info "No generated Aguafria Zig package catalog is on the classpath"
                      {:aguafria/phase :zig-package-catalog
                       :resource package-catalog-resource
                       :prepare-with "clojure -X:prepare-packages"})))
    (let [catalogs (mapv read-edn resources)
          installed (mapv install-catalog! catalogs)]
      (reset! installed-catalogs catalogs)
      {:catalog-count (count resources)
       :member-count (reduce + 0 (map :member-count installed))
       :namespace-count (reduce + 0 (map :namespace-count installed))
       :package-count (reduce + 0 (map :package-count installed))})))

(defn catalog-info
  "Return compact information for installed package catalogs."
  []
  (mapv #(dissoc % :namespaces) @installed-catalogs))

(defn namespaces
  "Return the symbols of all installed EDN-backed package namespaces."
  []
  (->> @installed-catalogs (mapcat :namespaces) (map :name) distinct
       (sort-by str) vec))

(defn entries
  "Return every installed package declaration, or those in one namespace."
  ([]
   (into [] (mapcat :members) (mapcat :namespaces @installed-catalogs)))
  ([namespace-name]
   (let [namespace-name (symbol (str namespace-name))]
     (or (some #(when (= namespace-name (:name %)) (:members %))
               (mapcat :namespaces @installed-catalogs))
         (throw (ex-info "Unknown EDN-backed Zig package namespace"
                         {:aguafria/phase :zig-package-catalog
                          :namespace namespace-name
                          :known (namespaces)}))))))
