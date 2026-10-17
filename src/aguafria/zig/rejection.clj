(ns aguafria.zig.rejection
  "Exact ordinary-build rejection receipts. Separate from positive artifact indexes.
  V1 deliberately supports only a self-contained generated namespace, embedded
  std/builtin, compiler-owned support, and a mapped constant-assignment error."
  (:require [aguafria.zig.artifact :as artifact]
            [aguafria.zig.toolchain :as toolchain]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files CopyOption StandardCopyOption]
           [java.security MessageDigest]
           [java.util HexFormat UUID]))

(def schema :aguafria-prepared-rejection-v1)

(defn- file-digest [path]
  (let [file (io/file path)]
    (when (.isFile file)
      (with-open [input (java.security.DigestInputStream.
                        (io/input-stream file) (MessageDigest/getInstance "SHA-256"))]
        (.transferTo input (java.io.OutputStream/nullOutputStream))
        (.formatHex (HexFormat/of) (.digest (.getMessageDigest input)))))))

(defn- fingerprints [paths]
  (let [result (into (sorted-map) (map (fn [path] [path (file-digest path)])) paths)]
    (when (and (seq result) (every? val result)) result)))

(defn- module-inputs [command]
  (into (sorted-map)
        (keep #(when-let [[_ module path] (re-matches #"-M([^=]+)=(.+)" %)]
                 [module path])) command))

(defn- closed-source? [source module]
  ;; Conservative lexical gate, not an import resolver. Any nonliteral import,
  ;; external file, or C import declines v1 rather than trusting cache-safe?.
  (let [imports (re-seq #"@import\s*\(\s*\"([^\"]+)\"\s*\)" source)]
    (and (not (re-find #"@(embedFile|cImport|cInclude|cDefine|cUndef)\b" source))
         (= (count imports) (count (re-seq #"@import\b" source)))
         (every? #{"std" "builtin" "root" module} (map second imports)))))

(defn- supported-command? [command support-path module]
  (and (vector? command) (<= 5 (count command))
       (= ["build-lib" "-dynamic"] (subvec command 1 3))
       (str/starts-with? (nth command 3) "-femit-bin=")
       (= support-path (nth command 4 nil))
       (loop [args (drop 5 command)]
         (if-let [arg (first args)]
           (cond
             (#{"-target" "-mcpu"} arg)
             (and (string? (second args))
                  (not (str/starts-with? (second args) "-"))
                  (recur (nnext args)))
             (= "--dep" arg)
             (and (= module (second args)) (recur (nnext args)))
             (or (#{"-fstrip" "-ferror-tracing" "-funwind-tables"} arg)
                 (re-matches #"-O(debug|safe|fast|small|ReleaseSafe|ReleaseFast|ReleaseSmall|Debug)" arg)
                 (re-matches #"-M[^=]+=.+" arg))
             (recur (next args))
             :else false)
           true))))

(defn freeze-inputs
  "Capture raw bytes/paths, not positive artifact's normalized source identity.
  Returns nil for unsupported recipes; never invokes the compiler or support."
  [{:keys [module-name command compiler-options panic-support artifact
           rejection-context? rejection-source-files]}]
  (try
    (let [module (str module-name)
          modules (module-inputs command)
          paths (vals modules)
          compiler (dissoc (toolchain/information) :materialized?)]
      (when (and rejection-context? (:cache-safe? compiler-options)
                 (string? (:executable compiler)) (= (first command) (:executable compiler))
                 (not (:native-test-context? artifact))
                 (not (:jvm-adapter? artifact)) (not (:native-host-context? artifact))
                 (= #{"root" module} (set (keys modules)))
                 (empty? (:module-zig-args compiler-options))
                 (supported-command? command (:path panic-support) module)
                 (seq rejection-source-files)
                 (every? #(.isAbsolute (io/file %)) rejection-source-files)
                 (every? #(closed-source? (slurp %) module) paths))
        (when-let [files (fingerprints (concat paths rejection-source-files [(:executable compiler)]))]
          {:files files :authored-files (vec (sort rejection-source-files))
           :modules modules
           :command (assoc command 3 "-femit-bin=<ordinary-output>")
           :toolchain compiler
           :support (select-keys panic-support [:path :hash :source :guard-source :command])
           :configuration (select-keys compiler-options
                                       [:optimize :target :cpu :development-debug-info
                                        :development-panic :module-cache-tokens
                                        :module-dependencies :zig-args :module-zig-args])})))
    (catch Exception _ nil)))

(defn recipe
  "Validate the frozen inputs and fingerprint the already-built support bytes.
  Missing support is a miss, never a request to construct or load support."
  [{:keys [rejection-inputs module-dir library-file panic-support] :as plan}]
  (when (and rejection-inputs (= rejection-inputs (freeze-inputs plan)))
    (when-let [support-files (fingerprints [(:path panic-support)
                                           (str (:source-file panic-support))
                                           (str (:guard-file panic-support))])]
      (when (and (= (:source panic-support) (slurp (:source-file panic-support)))
                 (= (:guard-source panic-support) (slurp (:guard-file panic-support))))
        {:schema schema :inputs rejection-inputs :support-files support-files
         :directory (.getAbsolutePath (io/file module-dir))
         :library-path (.getAbsolutePath (io/file library-file))}))))

(defn recipe-id [recipe] (artifact/key-for :compiler-rejection recipe))

(defn- receipt-file [cache-dir recipe]
  (io/file cache-dir "prepared-rejections" (str (recipe-id recipe) ".edn")))

(defn- source-rejection? [recipe result diagnostics]
  (and (= 1 (:exit result)) (string? (:err result)) (string? (:out result))
       (= (:directory recipe) (:directory result))
       (vector? (:command result))
       (= (get-in recipe [:inputs :command])
          (when (> (count (:command result)) 3)
            (assoc (:command result) 3 "-femit-bin=<ordinary-output>")))
       (str/starts-with? (get (:command result) 3 "") "-femit-bin=")
       (some (fn [{:keys [severity message file line] :as diagnostic}]
               (let [source (:aguafria/source diagnostic)]
                 (and (= :error severity) (= "cannot assign to constant" message)
                      (string? file) (pos-int? line) (pos-int? (:line source))
                      (string? (:file source))
                      (let [generated (if (.isAbsolute (io/file file)) file
                                          (.getAbsolutePath (io/file (:directory recipe) file)))]
                        (and (some #{generated} (vals (get-in recipe [:inputs :modules])))
                             (some #{(:file source)} (get-in recipe [:inputs :authored-files])))))))
             diagnostics)))

(defn publish!
  "Publish only a completed full ordinary build and mapped source diagnostic.
  Caller must compare the recipe again after the command. Exceptions are not results."
  [cache-dir recipe result diagnostics]
  (when (and recipe (source-rejection? recipe result diagnostics))
    (let [payload {:schema schema :recipe-id (recipe-id recipe) :recipe recipe
                   :outcome :compiler-rejected :verification :ordinary-namespace-load-full-build
                   :compiler-result result :diagnostics diagnostics}
          receipt (assoc payload :id (artifact/key-for :rejection-receipt payload))
          file (receipt-file cache-dir recipe)
          temporary (io/file (.getParentFile file) (str "." (UUID/randomUUID) ".edn"))]
      (io/make-parents file)
      (try
        (spit temporary (artifact/print-data receipt))
        ;; No non-atomic fallback: a filesystem without atomic publication cannot
        ;; supply a reusable receipt. Each reader sees a complete immutable value.
        (Files/move (.toPath temporary) (.toPath file)
                    (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                            StandardCopyOption/REPLACE_EXISTING]))
        receipt
        (finally (Files/deleteIfExists (.toPath temporary)))))))

(defn find-verified [cache-dir recipe]
  (when recipe
    (try
      (let [file (receipt-file cache-dir recipe)]
        (when (.isFile file)
          (let [receipt (edn/read-string (slurp file))]
            (when (and (= schema (:schema receipt))
                       (= :compiler-rejected (:outcome receipt))
                       (= :ordinary-namespace-load-full-build (:verification receipt))
                       (= recipe (:recipe receipt))
                       (= (recipe-id recipe) (:recipe-id receipt))
                       (= (:id receipt) (artifact/key-for :rejection-receipt (dissoc receipt :id)))
                       (= (get-in recipe [:inputs :files])
                          (fingerprints (keys (get-in recipe [:inputs :files]))))
                       (= (:support-files recipe) (fingerprints (keys (:support-files recipe))))
                       (not (.exists (io/file (:library-path recipe))))
                       (source-rejection? recipe (:compiler-result receipt) (:diagnostics receipt)))
              receipt))))
      (catch Exception _ nil))))
