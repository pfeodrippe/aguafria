(ns aguafria.zig.prepare
  "Prepare ordinary Clojure namespace entry points from Zig API catalogs.

  The catalog remains the authority; these small, ignored source files only
  make its namespaces discoverable by Clojure's normal loader and editors."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io PushbackReader]
           [java.nio.file Files Path]
           [java.security MessageDigest]
           [java.util HexFormat]))

(def ^:private generator "aguafria.zig.prepare")

(defn- form-op [form]
  (when (and (seq? form) (symbol? (first form))) (name (first form))))

(defn- slice-fields [pointer-type]
  [{:field-name "len" :signature "len: usize"
    :documentation "Number of elements in the slice."}
   {:field-name "ptr"
    :signature (str "ptr: " ((requiring-resolve 'aguafria.zig.emitter/emit-type) pointer-type))
    :documentation "Borrowed pointer to the slice's elements. Keep its backing storage alive; resizing or freeing that storage invalidates the pointer."}])

(defn type-fields
  "Discover built-in fields from structural type syntax. Conditional types expose
  only fields shared by every branch; unknown expressions never invent members."
  [expression]
  (cond
    (= "type" (form-op expression)) (type-fields (second expression))
    (= "return" (form-op expression)) (type-fields (second expression))
    (#{"if" "if-capture"} (form-op expression))
    (let [[left right] (map type-fields (take-last 2 expression))
          right (into {} (map (juxt :field-name identity)) right)]
      (into [] (keep (fn [field]
                       (when-let [other (get right (:field-name field))]
                         (if (= (:signature field) (:signature other))
                           field
                           (assoc field :signature (str (:field-name field) ": @TypeOf(self." (:field-name field) ")")))))) left))
    (vector? expression)
    (case (first expression)
      :slice (slice-fields [:many (second expression)])
      :slice-const (slice-fields [:many-const (second expression)])
      :pointer (when (= :slice (:size (second expression)))
                 (slice-fields (assoc expression 1 (assoc (second expression) :size :many))))
      (:array :array-sentinel) [{:field-name "len" :signature "len: usize" :documentation "Number of elements in the array."}]
      [])
    :else []))

(defn- type-namespace [sym]
  (symbol (str (namespace sym) "." (name sym))))

(defn- reference-path [expression]
  (cond
    (symbol? expression) (str expression)
    (= "field" (form-op expression))
    (when-let [owner (reference-path (second expression))]
      (str owner "." (name (nth expression 2))))))

(defn- resolve-type-reference [declarations owner expression]
  (when-let [path (reference-path expression)]
    (let [path (if (str/starts-with? path "std.") (subs path 4) path)
          parts (str/split path #"\.")
          suffix (str/join "." (butlast parts))]
      (loop [scope (str owner)]
        (let [candidate (symbol (str scope (when (seq suffix) (str "." suffix))) (last parts))]
          (cond
            (contains? declarations candidate) candidate
            (str/includes? scope ".") (recur (subs scope 0 (.lastIndexOf scope ".")))
            :else nil))))))

(defn- alias-reference [declarations member]
  (resolve-type-reference declarations (namespace (:symbol member)) (:type-expression member)))

(defn- member-type-fields [declarations member seen]
  (when-not (contains? seen (:symbol member))
    (or (seq (type-fields (:type-expression member)))
        (when-let [target (alias-reference declarations member)]
          (member-type-fields declarations (get declarations target) (conj seen (:symbol member)))))))

(defn- builtin-field-namespace [declarations member]
  (when-let [fields (seq (member-type-fields declarations member #{}))]
    (let [ns-name (type-namespace (:symbol member))]
      {:name ns-name
       :members
       (mapv (fn [{:keys [field-name] :as field}]
               (merge (select-keys member [:source :package :zig-alias :zig-version]) field
                      {:name (str "-" field-name) :clojure-name (str "-" field-name)
                       :symbol (symbol (str ns-name) (str "-" field-name))
                       :zig-name (str (:zig-name member) "." field-name)
                       :category :field :param-count 1
                       :parameters [{:name "self" :type "Self"}]})) fields)})))

(defn- alias-target [declarations known-namespaces member seen]
  (when-let [sym (alias-reference declarations member)]
    (when-not (contains? seen sym)
      (if (contains? known-namespaces (type-namespace sym))
        (get declarations sym)
        (alias-target declarations known-namespaces (get declarations sym) (conj seen sym))))))

(defn- alias-namespaces [declarations base known-namespaces member]
  (let [sym (:symbol member)
        alias-prefix (str (type-namespace sym))]
    (when-not (contains? known-namespaces (type-namespace sym))
      (when-let [target (alias-target declarations known-namespaces member #{sym})]
        (let [target-prefix (str (type-namespace (:symbol target)))]
          (for [ns-entry base
                :let [path (str (:name ns-entry))]
                :when (or (= path target-prefix) (str/starts-with? path (str target-prefix ".")))
                :let [ns-name (symbol (str alias-prefix (subs path (count target-prefix))))]]
            (assoc ns-entry :name ns-name
                   :members (mapv (fn [field]
                                    (assoc field
                                           :symbol (symbol (str ns-name) (:clojure-name field))
                                           :zig-name (str (:zig-name member)
                                                          (subs (:zig-name field) (count (:zig-name target))))))
                                  (:members ns-entry)))))))))

(defn- qualify-field-signature [declarations owner member]
  (assoc member :display-signature
         (if (not= :field (:category member))
           (:signature member)
           (str/replace (or (:signature member) "") #"\b[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*\b"
                        (fn [token]
                          (let [target (resolve-type-reference declarations owner (symbol token))]
                            (if (and target (not= target (:symbol member))) (str target) token)))))))

(defn- document-type-fields [qualified]
  (let [field-sections (into {} (for [{:keys [name members]} qualified
                                     :let [fields (filter #(= :field (:category %)) members)]
                                     :when (seq fields)]
                                 [(str name)
                                  (str "Fields:\n"
                                       (str/join "\n" (map #(str (:symbol %) " — " (:display-signature %)) fields)))]))]
    (mapv (fn [namespace]
            (update namespace :members
                    #(mapv (fn [member]
                             (if-let [section (get field-sections
                                                   (str (type-namespace (:symbol member))))]
                               (update member :documentation
                                       (fn [docs] (str (when (seq docs) (str docs "\n\n")) section)))
                               member)) %)))
          qualified)))

(defn enrich-namespaces
  "Share alias/type-field discovery and qualified tooltip signatures across std
  and package catalogs. Resolution is lexical and cycle-safe, never evaluation."
  [namespaces]
  (let [namespaces
        (mapv (fn [ns-entry]
                (update ns-entry :members
                        (fn [members]
                          (mapv (fn [member]
                                  ;; Signed numeric prefixes are number tokens to
                                  ;; Clojure's reader, even on field accessors.
                                  (let [member-name (:clojure-name member)
                                        readable-name (str/replace member-name #"^([+-])(?=[0-9])" "$1zig-")]
                                    (assoc member :clojure-name readable-name
                                                  :symbol (symbol (str (:name ns-entry)) readable-name))))
                                members))))
              namespaces)
        declarations (into {} (map (juxt :symbol identity)) (mapcat :members namespaces))
        known (set (map :name namespaces))
        builtins (keep (partial builtin-field-namespace declarations) (vals declarations))
        base (into (vec namespaces) (remove #(contains? known (:name %))) builtins)
        known (set (map :name base))
        aliases (mapcat (partial alias-namespaces declarations base known) (vals declarations))
        expanded (into base aliases)
        declarations (into {} (map (juxt :symbol identity)) (mapcat :members expanded))]
    (->> expanded
         (mapv (fn [ns-entry]
                 (update ns-entry :members
                         #(mapv (partial qualify-field-signature declarations (:name ns-entry)) %))))
         document-type-fields)))

(defn- read-edn [source]
  (with-open [reader (PushbackReader. (io/reader source))]
    (edn/read reader)))

(defn- digest [text]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes ^String text "UTF-8"))))

(defn- safe-path!
  "Refuse symlinks anywhere in an output path, including its ancestors."
  [^Path path]
  (loop [part path]
    (when part
      (when (Files/isSymbolicLink part)
        (throw (ex-info "Refusing a symlink in generated output" {:path (str part)})))
      (recur (.getParent part))))
  path)

(defn- entrypoint [kind {:keys [name members]}]
  (let [namespace-name name
        [prefix loader] (case kind
                          :std ["aguafria.std." 'aguafria.zig.std/install!]
                          :packages ["aguafria.pkg." 'aguafria.zig.package/install-namespace!])
        text (str namespace-name)]
    (when-not (and (symbol? namespace-name)
                   (str/starts-with? text prefix)
                   (re-matches #"[A-Za-z_][A-Za-z0-9_-]*(\.[A-Za-z_][A-Za-z0-9_-]*)+" text))
      (throw (ex-info "Invalid catalog namespace" {:kind kind :namespace namespace-name})))
    [(str (-> text (str/replace "." "/") (str/replace "-" "_")) ".clj")
     (str ";; Generated by " generator ". Do not edit.\n"
          "(ns " namespace-name "\n"
          "  (:refer-clojure :only [])\n"
          "  (:require [" (namespace loader) "]))\n\n"
          (when (#{:std :packages} kind)
            (apply str
                   (for [{:keys [clojure-name signature display-signature documentation parameters]} members]
                     (str "(clojure.core/when (clojure.core/class? (clojure.core/ns-resolve clojure.core/*ns* '" clojure-name "))\n"
                          "  (clojure.core/ns-unmap clojure.core/*ns* '" clojure-name "))\n"
                          "(clojure.core/declare ^"
                          (pr-str
                           (cond-> {(if (= kind :std) :aguafria/std :aguafria/package) true
                                    :doc (str (or display-signature signature) "\n\n" documentation)}
                             (and (seq parameters) (every? :name parameters))
                             (assoc :arglists
                                    (list 'quote (list (mapv (comp symbol :name) parameters))))))
                          " " clojure-name ")\n"))))
          "\n(" loader " clojure.core/*ns*)\n")]))

(defn write-entrypoints!
  "Refresh one catalog's entry points in `generated-dir` (default `generated`).

  Only manifest-owned, unchanged generated files may be replaced or removed.
  Unrelated files and edited generated files are preserved. Removed package
  namespaces disappear on the next prep run; restart the REPL after prep to
  discard already-loaded namespaces. Returns paths and counts, not the catalog."
  [{:keys [kind namespaces generated-dir]
    :or {generated-dir "generated"}}]
  (when-not (and (#{:std :packages} kind)
                 (string? generated-dir)
                 (not (str/blank? generated-dir)))
    (throw (ex-info "Expected a catalog kind and generated directory"
                    {:kind kind :generated-dir generated-dir})))
  (locking #'write-entrypoints!
    (let [root (safe-path! (.normalize (.toAbsolutePath (.toPath (io/file generated-dir)))))
          manifest-path (.resolve root (str ".aguafria-" (name kind) "-entrypoints.edn"))
          _ (safe-path! manifest-path)
          manifest (when (.exists (.toFile manifest-path)) (read-edn (.toFile manifest-path)))
          _ (when (and manifest
                       (not (and (= generator (:generator manifest))
                                 (= kind (:kind manifest))
                                 (map? (:files manifest)))))
              (throw (ex-info "Unrecognized generated manifest" {:path (str manifest-path)})))
          entries (mapv #(entrypoint kind %)
                        (remove #(= 'aguafria.std (:name %)) namespaces))
          sources (into (sorted-map) entries)
          _ (when-not (= (count entries) (count sources))
              (throw (ex-info "Catalog namespaces have colliding resource paths" {:kind kind})))
          previous (:files manifest)
          all-paths (sort (into (set (keys sources)) (keys previous)))
          stale (remove #(contains? sources %) (keys previous))]
      ;; Validate the complete write/delete set before changing any file.
      (doseq [relative all-paths]
        (when-not (and (string? relative)
                       (re-matches #"aguafria/(std|pkg)/[A-Za-z0-9_/]+\.clj" relative)
                       (str/starts-with? relative (if (= kind :std) "aguafria/std/" "aguafria/pkg/")))
          (throw (ex-info "Unsafe generated manifest path" {:path relative})))
        (let [path (safe-path! (.resolve root ^String relative))
              file (.toFile path)]
          (when (.exists file)
            (when-not (and (.isFile file)
                           (if-let [expected (get previous relative)]
                             (= expected (digest (slurp file)))
                             (= (get sources relative) (slurp file))))
              (throw (ex-info "Refusing to overwrite or remove an edited/non-generated file"
                              {:path (str path)}))))))
      (doseq [[relative source] sources]
        (let [file (.toFile (.resolve root ^String relative))]
          (when-not (and (.isFile file) (= source (slurp file)))
            (io/make-parents file)
            (spit file source))))
      (doseq [relative stale]
        (Files/deleteIfExists (.resolve root ^String relative)))
      (io/make-parents (.toFile manifest-path))
      (spit (.toFile manifest-path)
            (str (pr-str {:generator generator :kind kind
                          :files (into (sorted-map)
                                       (map (fn [[path source]] [path (digest source)]))
                                       sources)}) "\n"))
      {:generated-dir (str root)
       :namespace-count (count sources)
       :removed-count (count stale)})))

(defn std!
  "Generate the std metadata catalog and entry points in ignored generated/.
  Use `clojure -X:prepare` in an Aguafria source checkout. The first preparation
  needs Node.js and the pinned Zig toolchain; unchanged inputs reuse the cache.
  Published JARs include prepared output; source consumers use deps prep."
  [options]
  ((requiring-resolve 'aguafria.generate-keyword/prepare-std!) options))
