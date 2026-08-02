(ns aguafria.zig.convert
  "Convert compiler-parsed Zig source into inspectable Aguafria namespaces."
  (:require [aguafria.keyword :as keyword]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.std :as zig-std]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import [java.io File PushbackReader]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path StandardOpenOption]
           [java.security MessageDigest]
           [java.util HexFormat]))

(def ^:private ast-resource "aguafria/zig-ast.zig")
(def ^:private ast-schema-version 3)
(defonce ^:private helper-lock (Object.))
(defonce ^:private conversion-history (atom []))

(declare record-conversion!)

(defn- sha256
  [value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes (str value) StandardCharsets/UTF_8))
    (.formatHex (HexFormat/of) (.digest digest))))

(defn- run-command
  [command directory]
  (let [builder (doto (ProcessBuilder. ^java.util.List (mapv str command))
                  (.directory (io/file directory)))
        process (.start builder)
        stdout (future (slurp (.getInputStream process)))
        stderr (future (slurp (.getErrorStream process)))
        exit (.waitFor process)]
    {:command (mapv str command)
     :directory (str directory)
     :exit exit
     :out @stdout
     :err @stderr}))

(defn- run-command-input
  [command directory input]
  (let [builder (doto (ProcessBuilder. ^java.util.List (mapv str command))
                  (.directory (io/file directory)))
        process (.start builder)
        stdout (future (slurp (.getInputStream process)))
        stderr (future (slurp (.getErrorStream process)))]
    (with-open [stream (.getOutputStream process)]
      (.write stream (.getBytes (str input) StandardCharsets/UTF_8)))
    (let [exit (.waitFor process)]
      {:command (mapv str command)
       :directory (str directory)
       :exit exit
       :out @stdout
       :err @stderr})))

(defn- executable-name
  [base]
  (if (str/starts-with? (str/lower-case (System/getProperty "os.name")) "windows")
    (str base ".exe")
    base))

(defn- helper-source
  []
  (if-let [resource (io/resource ast-resource)]
    (slurp resource)
    (throw (ex-info "Aguafria's Zig AST helper resource is missing"
                    {:resource ast-resource}))))

(defn- ensure-helper!
  [{:keys [cache-dir zig]
    :or {cache-dir ".aguafria/zig" zig "zig"}}]
  (locking helper-lock
    (let [source (helper-source)
          version-result (run-command [zig "version"] (System/getProperty "user.dir"))]
      (when-not (zero? (:exit version-result))
        (throw (ex-info "Unable to query Zig while preparing the source converter"
                        version-result)))
      (let [zig-version (str/trim (:out version-result))
            hash (subs (sha256 [source zig-version]) 0 24)
            directory (io/file cache-dir "tools" "zig-ast" hash)
            source-file (io/file directory "zig-ast.zig")
            executable (io/file directory (executable-name "zig-ast"))]
        (.mkdirs directory)
        (when-not (= source (when (.isFile source-file) (slurp source-file)))
          (Files/writeString (.toPath source-file) source StandardCharsets/UTF_8
                             (into-array StandardOpenOption
                                         [StandardOpenOption/CREATE
                                          StandardOpenOption/TRUNCATE_EXISTING
                                          StandardOpenOption/WRITE])))
        (when-not (and (.isFile executable) (pos? (.length executable)))
          (let [result (run-command
                        [zig "build-exe" "-OReleaseFast"
                         (str "-femit-bin=" (.getAbsolutePath executable))
                         (.getAbsolutePath source-file)]
                        (.getAbsolutePath directory))]
            (when-not (zero? (:exit result))
              (throw (ex-info
                      (str "Could not compile Aguafria's Zig AST helper\n\n"
                           (:err result))
                      (assoc result :aguafria/phase :zig-ast-helper))))))
        {:path (.getAbsolutePath executable)
         :source-path (.getAbsolutePath source-file)
         :hash hash
         :zig-version zig-version}))))

(defn- byte-slice
  [^bytes bytes start end]
  (String. bytes (int start) (int (- end start)) StandardCharsets/UTF_8))

(defn- source-line
  [source line]
  (nth (str/split-lines source) (dec line) nil))

(defn- parse-error-message
  [path source [tag _token line column _previous?]]
  (let [text (source-line source line)
        gutter (apply str (repeat (count (str line)) " "))]
    (str "error[aguafria::zig-parse]: " (name tag) "\n"
         "  --> " path ":" line ":" column "\n"
         (when text
           (str " " gutter " |\n"
                " " line " | " text "\n"
                " " gutter " | " (apply str (repeat (max 0 (dec column)) " "))
                "^ Zig 0.16 rejected this syntax\n")))))

(defn- index-by-first
  [entries]
  (into {} (map (juxt first identity)) entries))

(defn parse-file
  "Parse a Zig file with the selected Zig compiler's own `std.zig.Ast`.

  Returns source bytes, normalized node/token tables, semantic helper indexes,
  the Zig version, and source path. Throws a source-located ExceptionInfo for
  malformed Zig."
  ([path] (parse-file path {}))
  ([path options]
   (let [file (.getCanonicalFile (io/file path))]
     (when-not (.isFile file)
       (throw (ex-info "Zig conversion input is not a regular file"
                       {:path (str path) :resolved (.getAbsolutePath file)})))
     (let [helper (or (::helper options) (ensure-helper! options))
           result (run-command [(:path helper) (.getAbsolutePath file)]
                               (System/getProperty "user.dir"))]
       (when-not (zero? (:exit result))
         (throw (ex-info (str "Zig AST extraction failed for " file "\n\n"
                              (:err result))
                         (assoc result :aguafria/phase :zig-ast-parse
                                :path (.getAbsolutePath file)))))
       (let [raw (try
                   (edn/read-string (:out result))
                   (catch Throwable error
                     (throw (ex-info "Zig AST helper returned unreadable EDN"
                                     {:path (.getAbsolutePath file)
                                      :stdout (:out result)
                                      :stderr (:err result)}
                                     error))))
             bytes (Files/readAllBytes (.toPath file))
             source (String. bytes StandardCharsets/UTF_8)]
         (when-not (= ast-schema-version (:schema-version raw))
           (throw (ex-info "Unsupported Zig AST helper schema"
                           {:expected ast-schema-version
                            :actual (:schema-version raw)})))
         (when-let [error (first (:errors raw))]
           (throw (ex-info (parse-error-message (.getAbsolutePath file) source error)
                           {:aguafria/phase :zig-parse
                            :path (.getAbsolutePath file)
                            :errors (:errors raw)
                            :zig-version (:zig-version helper)})))
         (-> raw
             (assoc :path (.getAbsolutePath file)
                    :source source
                    :source-bytes bytes
                    :zig-version (:zig-version helper)
                    :helper (dissoc helper :path))
             (update :nodes
                     #(mapv (fn [[tag main first-token last-token data-kind a b]]
                              {:tag tag :main-token main
                               :first-token first-token :last-token last-token
                               :data-kind data-kind :a a :b b})
                            %))
             (as-> context
                 (assoc context :node-span-index
                        (into {}
                              (map-indexed
                               (fn [node-index {:keys [first-token last-token]}]
                                 [[first-token last-token] node-index])
                               (:nodes context)))))
             (assoc :function-index (index-by-first (:functions raw))
                    :function-prototype-index
                    (index-by-first (:function-prototypes raw))
                    :test-index (index-by-first (:tests raw))
                    :var-index (index-by-first (:var-decls raw))
                    :assign-destructure-index
                    (index-by-first (:assign-destructures raw))
                    :block-index (index-by-first (:blocks raw))
                    :call-index (index-by-first (:calls raw))
                    :builtin-index (index-by-first (:builtins raw))
                    :array-init-index (index-by-first (:array-inits raw))
                    :struct-init-index (index-by-first (:struct-inits raw))
                    :if-index (index-by-first (:ifs raw))
                    :while-index (index-by-first (:whiles raw))
                    :for-index (index-by-first (:fors raw))
                    :switch-index (index-by-first (:switches raw))
                    :switch-case-index (index-by-first (:switch-cases raw))
                    :array-type-index (index-by-first (:array-types raw))
                    :ptr-type-index (index-by-first (:ptr-types raw))
                    :slice-index (index-by-first (:slices raw))
                    :container-index (index-by-first (:containers raw))
                    :container-field-index (index-by-first (:container-fields raw)))))))))

(def ^:private primitive-type?
  (let [fixed #{"anyerror" "anyframe" "anyopaque" "anytype" "bool" "c_char"
                "c_int" "c_long" "c_longdouble" "c_longlong" "c_short"
                "c_uint" "c_ulong" "c_ulonglong" "c_ushort" "comptime_float"
                "comptime_int" "f16" "f32" "f64" "f80" "f128" "isize"
                "noreturn" "type" "usize" "void"}]
    #(or (contains? fixed %)
         (boolean (re-matches #"[iuf][0-9]+" %)))))

(def ^:private binary-operators
  {:equal_equal '== :bang_equal '!= :less_than '< :greater_than '>
   :less_or_equal '<= :greater_or_equal '>=
   :mul '* :div '/ :mod '% :add '+ :sub '-
   :mul_wrap '*% :add_wrap '+% :sub_wrap '-%
   :mul_sat '*| :add_sat '+| :sub_sat '-|
   :shl '<< :shl_sat (symbol "op") :shr '>>
   :bit_and '& :bit_or '| :bit_xor 'ak/bit-xor
   :bool_and 'and :bool_or 'or
   :array_cat (symbol "op") :array_mult (symbol "op")
   :merge_error_sets (symbol "op") :orelse 'orelse :catch 'catch
   :switch_range (symbol "op") :for_range (symbol "op")})

(def ^:private binary-operator-tokens
  {:shl_sat "<<|" :array_cat "++" :array_mult "**"
   :merge_error_sets "||" :switch_range "..." :for_range ".."})

(def ^:private assignment-tokens
  {:assign "=" :assign_mul "*=" :assign_div "/=" :assign_mod "%="
   :assign_add "+=" :assign_sub "-=" :assign_shl "<<="
   :assign_shl_sat "<<|=" :assign_shr ">>=" :assign_bit_and "&="
   :assign_bit_xor "^=" :assign_bit_or "|=" :assign_mul_wrap "*%="
   :assign_add_wrap "+%=" :assign_sub_wrap "-%=" :assign_mul_sat "*|="
   :assign_add_sat "+|=" :assign_sub_sat "-|="})

(def ^:private simple-assignment-symbols
  {"+=" '+= "-=" '-= "*=" '*= "%=" '%=
   "+%=" '+%= "-%=" '-%= "*%=" '*%=
   "+|=" '+|= "-|=" '-|= "*|=" '*|=
   "&=" '&= "|=" '|= "<<=" '<<= ">>=" '>>=})

(def ^:private builtin-symbols
  (delay
    (into {}
          (map (fn [{:keys [name zig-name]}]
                 [zig-name (symbol "ak" name)]))
          (keyword/entries))))

(def ^:private std-members
  (delay
    (into {} (map (juxt :zig-name identity)) (zig-std/entries))))

(defn- token
  [context token-index]
  (nth (:tokens context) token-index nil))

(defn- token-text
  [context token-index]
  (when-let [[_ start length] (token context token-index)]
    (byte-slice (:source-bytes context) start (+ start length))))

(defn- token-start
  [context token-index]
  (second (token context token-index)))

(defn- token-end
  [context token-index]
  (let [[_ start length] (token context token-index)]
    (+ start length)))

(defn- node
  [context node-index]
  (nth (:nodes context) node-index nil))

(defn- node-range
  [context node-index]
  (let [{:keys [first-token last-token]} (node context node-index)]
    [(token-start context first-token) (token-end context last-token)]))

(defn- node-source
  [context node-index]
  (let [[start end] (node-range context node-index)]
    (byte-slice (:source-bytes context) start end)))

(defn- safe-identifier
  [text]
  (when (and (string? text)
             (re-matches #"[A-Za-z_][A-Za-z0-9_]*" text)
             ;; These spellings construct symbols programmatically but read
             ;; back as Clojure literals after generated source is written.
             (not (contains? #{"nil" "true" "false"} text)))
    (symbol text)))

(defn- declaration-reference-symbol
  [context text]
  (when-let [clojure-name (get (:declaration-names context) text)]
    (if (and (= (str clojure-name) text)
             (not (emitter/structural-operator? clojure-name)))
      clojure-name
      (with-meta clojure-name {:zig/name text :zig/reference true}))))

(def ^:private jvm-utf8-chunk-bytes 24000)

(defn- utf8-chunks
  [source]
  (let [iterator (.iterator (.codePoints ^String source))]
    (loop [chunks [] builder (StringBuilder.) bytes 0]
      (if (.hasNext iterator)
        (let [code-point (.nextInt iterator)
              chars (String. (Character/toChars code-point))
              char-bytes (alength (.getBytes chars StandardCharsets/UTF_8))]
          (if (and (pos? bytes) (> (+ bytes char-bytes) jvm-utf8-chunk-bytes))
            (recur (conj chunks (str builder)) (doto (StringBuilder.) (.append chars))
                   char-bytes)
            (do (.append builder chars)
                (recur chunks builder (+ bytes char-bytes)))))
        (cond-> chunks
          (pos? (.length builder)) (conj (str builder)))))))

(defn- raw-form
  [source]
  (let [chunks (utf8-chunks source)]
    (if (= 1 (count chunks))
      (list 'raw source)
      (list 'raw-chunks chunks))))

(def ^:private raw-boundary-ops
  '#{raw raw-chunks raw-statements raw-statement-chunks})

(defn- raw-boundary?
  [form]
  (and (seq? form) (contains? raw-boundary-ops (first form))))

(defn- contains-raw-boundary?
  [form]
  (cond
    (raw-boundary? form) true
    (map? form) (boolean (some contains-raw-boundary? (mapcat identity form)))
    (coll? form) (boolean (some contains-raw-boundary? form))
    :else false))

(defn- record-fallback!
  [context node-index role reason]
  (let [[start end] (node-range context node-index)
        fallback {:node node-index
                  :tag (:tag (node context node-index))
                  :role role
                  :reason reason
                  :start-byte start
                  :end-byte end
                  :source (node-source context node-index)}]
    (swap! (:fallbacks context) conj fallback)
    (raw-form (:source fallback))))

(defn- statement-source
  [context node-index]
  (let [{:keys [first-token last-token]} (node context node-index)
        following (inc last-token)
        end-token (if (= :semicolon (first (token context following)))
                    following
                    last-token)]
    (byte-slice (:source-bytes context)
                (token-start context first-token)
                (token-end context end-token))))

(defn- record-statement-fallback!
  [context node-index reason]
  (let [[start _] (node-range context node-index)
        source (statement-source context node-index)
        fallback {:node node-index
                  :tag (:tag (node context node-index))
                  :role :statement
                  :reason reason
                  :start-byte start
                  :end-byte (+ start (alength (.getBytes source StandardCharsets/UTF_8)))
                  :source source}]
    (swap! (:fallbacks context) conj fallback)
    (let [chunks (utf8-chunks source)]
      (if (= 1 (count chunks))
        (list 'raw-statements source)
        (list 'raw-statement-chunks chunks)))))

(defn- std-alias
  [context namespace-symbol]
  (or (get @(:std-aliases context) namespace-symbol)
      (let [suffix (subs (str namespace-symbol) (count "aguafria.std"))
            alias (if (str/blank? suffix)
                    'zig-std
                    (symbol (str "std-"
                                 (-> suffix
                                     (str/replace-first #"^\." "")
                                     (str/replace "." "-")
                                     (str/replace "_" "-")))))]
        (swap! (:std-aliases context) assoc namespace-symbol alias)
        alias)))

(defn- std-reference
  [context segments]
  (when (and (= "std" (first segments)) (< 1 (count segments)))
    (when-let [member (get @std-members
                          (str "@import(\"std\")." (str/join "." (rest segments))))]
      (let [canonical (:symbol member)
            namespace-symbol (symbol (namespace canonical))]
        (symbol (str (std-alias context namespace-symbol)) (name canonical))))))

(defn- project-reference
  [context segments]
  (when-let [{:keys [alias namespace declarations]}
             (get (:import-bindings context) (first segments))]
    (when-let [member-name (second segments)]
      (when-let [clojure-name (get declarations member-name)]
        (swap! (:project-aliases context) assoc namespace alias)
        (reduce (fn [target field-name]
                  (list 'field target
                        (or (safe-identifier field-name)
                            (list 'identifier-literal field-name))))
                (symbol (str alias) (str clojure-name))
                (drop 2 segments))))))

(declare translate-expr translate-type translate-stmt translate-block
         translate-switch translate-container translate-while
         translate-for
         translate-function-type
         capture-forms
         translate-function-declaration translate-var-declaration
         translate-test-declaration translate-comptime-declaration
         translate-container-field-declaration translate-function-prototype)

(defn- field-segments
  [context node-index]
  (let [{:keys [tag a b]} (node context node-index)]
    (cond
      (= :identifier tag) [(token-text context (:main-token (node context node-index)))]
      (= :field_access tag)
      (when-let [prefix (field-segments context a)]
        (conj prefix (token-text context b)))
      :else nil)))

(defn- translate-field
  [context node-index]
  (let [{:keys [a b]} (node context node-index)
        segments (field-segments context node-index)
        field-source (token-text context b)]
    (or (std-reference context segments)
        (project-reference context segments)
        (list 'field
              (translate-expr context a)
              (or (safe-identifier field-source)
                  (list 'identifier-literal field-source))))))

(defn- parse-number
  [source]
  (when (re-matches #"-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?" source)
    (try
      (let [value (edn/read-string source)]
        (when (number? value) value))
      (catch Throwable _ nil))))

(defn- parse-string
  [source]
  (try
    (let [value (edn/read-string source)]
      (when (string? value) value))
    (catch Throwable _ nil)))

(defn- multiline-string-lines
  [source]
  ;; The AST span may contain blank lines and comments between consecutive
  ;; multiline-string tokens. They are trivia, not string contents.
  (let [lines (->> (str/split source #"\r?\n")
                   (keep (fn [line]
                           (let [line (str/triml line)]
                             (when (str/starts-with? line "\\\\")
                               (subs line 2)))))
                   vec)]
    (when-not (seq lines)
      (throw (ex-info "Zig AST returned an invalid multiline string token"
                      {:source source})))
    lines))

(defn- translate-array-init
  [context node-index]
  (let [[_ type-node element-nodes] (get (:array-init-index context) node-index)
        elements (mapv #(translate-expr context %) element-nodes)]
    (if type-node
      (list 'array-init (translate-type context type-node) elements)
      elements)))

(defn- field-name-before
  [context node-index]
  (loop [token-index (dec (:first-token (node context node-index)))]
    (when (pos? token-index)
      (let [[tag] (token context token-index)]
        (cond
          (= :equal tag)
          (let [name-token (dec token-index)]
            (when (= :identifier (first (token context name-token)))
              (token-text context name-token)))

          (#{:l_brace :comma} tag) nil
          :else (recur (dec token-index)))))))

(defn- translate-struct-init
  [context node-index]
  (let [[_ type-node field-nodes] (get (:struct-init-index context) node-index)
        fields (reduce (fn [result field-node]
                         (if-let [field-name (field-name-before context field-node)]
                           ;; Zig's quoted identifiers (`.@"test"`) are legal
                           ;; map-field names but not readable Clojure keywords.
                           ;; A string remains ordinary inspectable data and the
                           ;; emitter renders it back without changing spelling.
                           (assoc result (if (safe-identifier field-name)
                                           (keyword field-name)
                                           field-name)
                                  (translate-expr context field-node))
                           (reduced nil)))
                       (array-map)
                       field-nodes)]
    (if fields
      (if type-node
        (list 'init (translate-type context type-node) fields)
        fields)
      (record-fallback! context node-index :expression :tuple-or-quoted-struct-init))))

(defn- translate-call
  [context node-index]
  (let [[_ function-node argument-nodes] (get (:call-index context) node-index)]
    (apply list (translate-expr context function-node)
           (map #(translate-expr context %) argument-nodes))))

(defn- translate-builtin
  [context node-index]
  (let [[_ argument-nodes] (get (:builtin-index context) node-index)
        zig-name (token-text context (:main-token (node context node-index)))]
    (if-let [builtin (get @builtin-symbols zig-name)]
      (apply list builtin (map #(translate-expr context %) argument-nodes))
      (record-fallback! context node-index :expression :unknown-zig-builtin))))

(defn- catch-captures
  [context left-node right-node]
  (let [start (inc (:last-token (node context left-node)))
        end (:first-token (node context right-node))
        opening-pipe (some (fn [token-index]
                             (when (= :pipe (first (token context token-index)))
                               token-index))
                           (range start end))]
    (when opening-pipe
      (capture-forms context (inc opening-pipe)))))

(defn- destructure-binding
  [context node-index]
  (if-let [[_ visibility extern-token _lib threadlocal comptime-token mut-token
            type-node align-node addrspace-node section-node _init-node]
           (get (:var-index context) node-index)]
    (let [zig-name (token-text context (inc mut-token))
          name (safe-identifier zig-name)
          kind (case (first (token context mut-token))
                 :keyword_const :const
                 :keyword_var :var
                 nil)]
      (if (and kind name (nil? visibility) (nil? extern-token)
               (nil? threadlocal))
        (cond-> {:kind kind :name name}
          comptime-token (assoc :prefix (token-text context comptime-token))
          type-node (assoc :type (translate-type context type-node))
          align-node (assoc :align (translate-expr context align-node))
          addrspace-node (assoc :addrspace (translate-expr context addrspace-node))
          section-node (assoc :linksection (translate-expr context section-node)))
        (record-fallback! context node-index :expression
                          :qualified-destructure-binding)))
    (let [value (translate-expr context node-index)]
      (if (= '_ value)
        {:kind :discard}
        {:kind :target :target value}))))

(defn- translate-destructure
  [context node-index]
  (let [[_ variables value-node comptime-token]
        (get (:assign-destructure-index context) node-index)]
    (list 'destructure
          (cond-> {} comptime-token (assoc :comptime? true))
          (mapv #(destructure-binding context %) variables)
          (translate-expr context value-node))))

(defn- translate-if
  ([context node-index] (translate-if context node-index false))
  ([context node-index statement?]
   (let [[_ condition then-node else-node payload-token error-token]
         (get (:if-index context) node-index)
         payload (capture-forms context payload-token)
         error (capture-forms context error-token)
         branch (fn [branch-node]
                  (cond
                    (contains? (:block-index context) branch-node)
                    (translate-block context branch-node)

                    (contains? #{:return :break :continue}
                               (:tag (node context branch-node)))
                    (translate-stmt context branch-node)

                    :else
                    ((if statement? translate-stmt translate-expr)
                     context branch-node)))
         options (cond-> {}
                   (seq payload) (assoc :payload payload)
                   (seq error) (assoc :error error))
         args (cond-> [(translate-expr context condition)
                       (branch then-node)]
                else-node (conj (branch else-node)))]
     (if (seq options)
       (apply list (if statement? 'if-capture-stmt 'if-capture)
              options args)
       (apply list 'if args)))))

(defn- translate-slice
  [context node-index]
  (let [[_ sliced start end sentinel] (get (:slice-index context) node-index)]
    (if sentinel
      (list 'slice-sentinel
            (translate-expr context sliced)
            (translate-expr context start)
            (when end (translate-expr context end))
            (translate-expr context sentinel))
      (cond-> (list 'slice (translate-expr context sliced)
                    (translate-expr context start))
        end (concat [(translate-expr context end)])
        true vec
        true seq))))

(defn- translate-expr*
  "Translate one parsed Zig expression node into Aguafria form data."
  [context node-index]
  (let [{:keys [tag main-token data-kind a b]} (node context node-index)]
    (cond
      (contains? (:call-index context) node-index)
      (translate-call context node-index)

      (contains? (:builtin-index context) node-index)
      (translate-builtin context node-index)

      (contains? (:array-init-index context) node-index)
      (translate-array-init context node-index)

      (contains? (:struct-init-index context) node-index)
      (translate-struct-init context node-index)

      (contains? (:slice-index context) node-index)
      (translate-slice context node-index)

      (contains? (:if-index context) node-index)
      (translate-if context node-index)

      (contains? (:switch-index context) node-index)
      (translate-switch context node-index)

      (contains? (:while-index context) node-index)
      (translate-while context node-index)

      (contains? (:for-index context) node-index)
      (translate-for context node-index)

      (contains? (:container-index context) node-index)
      (translate-container context node-index)

      (contains? (:assign-destructure-index context) node-index)
      (translate-destructure context node-index)

      (or (contains? (:ptr-type-index context) node-index)
          (contains? (:array-type-index context) node-index)
          (contains? (:function-prototype-index context) node-index)
          (contains? #{:optional_type :error_union :error_set_decl} tag))
      (list 'type (translate-type context node-index))

      (contains? assignment-tokens tag)
      (list 'assign-expr (get assignment-tokens tag)
            (translate-expr context a)
            (translate-expr context b))

      (contains? #{:return :break :continue} tag)
      (translate-stmt context node-index)

      (= :identifier tag)
      (let [text (token-text context main-token)]
        (case text
          "true" true
          "false" false
          (or (declaration-reference-symbol context text)
              (safe-identifier text)
              (list 'identifier-literal text))))

      (= :number_literal tag)
      (or (parse-number (node-source context node-index))
          (list 'number-literal (node-source context node-index)))

      (= :string_literal tag)
      (or (parse-string (node-source context node-index))
          (list 'string-literal (node-source context node-index)))

      (= :multiline_string_literal tag)
      (list 'multiline-string (multiline-string-lines
                               (node-source context node-index)))

      (= :char_literal tag)
      (list 'char-literal (node-source context node-index))

      (= :enum_literal tag)
      (let [source (node-source context node-index)]
        (if (re-matches #"\.[A-Za-z_][A-Za-z0-9_]*" source)
          (keyword source)
          (list 'enum-literal source)))

      (= :error_value tag)
      (let [source (node-source context node-index)
            error-name (str/replace-first source #"^error\." "")]
        (list 'error-value
              (or (safe-identifier error-name)
                  (list 'identifier-literal error-name))))

      (= :field_access tag) (translate-field context node-index)
      (= :grouped_expression tag) (translate-expr context a)
      (= :unwrap_optional tag) (list 'unwrap (translate-expr context a))
      (= :deref tag) (list 'deref (translate-expr context a))
      (= :array_access tag)
      (list 'index (translate-expr context a) (translate-expr context b))

      (= :catch tag)
      (let [captures (catch-captures context a b)]
        (if (seq captures)
          (list 'catch-capture captures
                (translate-expr context a)
                (translate-expr context b))
          (list 'catch (translate-expr context a) (translate-expr context b))))

      (contains? binary-operators tag)
      (let [operator (get binary-operators tag)
            operands (cond-> [(translate-expr context a)]
                       (some? b) (conj (translate-expr context b)))]
        (if (= 'op operator)
          (apply list 'op (get binary-operator-tokens tag) operands)
          (apply list operator operands)))

      (= :bool_not tag) (list '! (translate-expr context a))
      (= :negation tag) (list '- (translate-expr context a))
      (= :negation_wrap tag)
      (list 'op "-%" (translate-expr context a))
      (= :bit_not tag) (list 'ak/bit-not (translate-expr context a))
      (= :address_of tag) (list '& (translate-expr context a))
      (= :try tag) (list 'try (translate-expr context a))
      (= :comptime tag) (list 'comptime (translate-expr context a))
      (= :nosuspend tag) (list 'nosuspend (translate-expr context a))
      (= :unreachable_literal tag) 'unreachable
      (contains? (:block-index context) node-index)
      (translate-block context node-index)

      :else
      (record-fallback! context node-index :expression
                        (keyword (str "unsupported-" (name tag)))))))

(defn translate-expr
  "Translate one parsed Zig expression node into Aguafria form data.

  Unsupported leaves are explicit `raw` boundaries. If such a leaf occurs
  inside a larger expression, preserve that compiler-delimited larger node as
  one boundary so punctuation and multiline literal layout cannot be changed
  accidentally by a partially structural parent."
  [context node-index]
  (let [form (translate-expr* context node-index)]
    (if (and (not (raw-boundary? form)) (contains-raw-boundary? form))
      (record-fallback! context node-index :expression :nested-fallback)
      form)))

(defn- simple-pointer-type
  [context node-index]
  (let [[_ size allowzero const-token volatile align-node addrspace-node
         sentinel-node bit-start bit-end child-node]
        (get (:ptr-type-index context) node-index)]
    (let [child (translate-type context child-node)
          qualified? (or (some identity [allowzero volatile align-node addrspace-node
                                         bit-start bit-end])
                         (and (= :c size) const-token)
                         (and (= :slice size) sentinel-node))]
      (if qualified?
        [:pointer
         (cond-> {:size size}
           const-token (assoc :const? true)
           volatile (assoc :volatile? true)
           allowzero (assoc :allowzero? true)
           align-node (assoc :align (translate-expr context align-node))
           addrspace-node (assoc :addrspace (translate-expr context addrspace-node))
           sentinel-node (assoc :sentinel (translate-expr context sentinel-node))
           bit-start (assoc :bit-start (translate-expr context bit-start))
           bit-end (assoc :bit-end (translate-expr context bit-end)))
         child]
        (case size
          :one (if const-token [:*const child] [:* child])
          :many (cond
                  sentinel-node [(if const-token :sentinel-const :sentinel)
                                 child (translate-expr context sentinel-node)]
                  const-token [:many-const child]
                  :else [:many child])
          :slice (if const-token [:slice-const child] [:slice child])
          :c (if const-token
               (record-fallback! context node-index :type :const-c-pointer)
               [:c-pointer child])
          (record-fallback! context node-index :type :unknown-pointer-size))))))

(defn- error-set-members
  [context node-index]
  (let [{:keys [first-token last-token]} (node context node-index)]
    (->> (range first-token (inc last-token))
         (keep (fn [token-index]
                 (when (= :identifier (first (token context token-index)))
                   (let [source (token-text context token-index)]
                     (or (safe-identifier source)
                         (list 'identifier-literal source))))))
         vec)))

(defn- function-type-arguments
  [context params]
  (mapv
   (fn [[name-token type-node qualifier-token anytype-token]]
     (let [special (when anytype-token (token-text context anytype-token))]
       (cond-> {:name (some->> name-token (token-text context) safe-identifier)}
         qualifier-token
         (assoc :prefix (token-text context qualifier-token))

         (= "..." special)
         (assoc :variadic? true)

         (not= "..." special)
         (assoc :type (cond
                        type-node (translate-type context type-node)
                        (= "anytype" special) :anytype
                        :else :anytype)))))
   params))

(defn- translate-function-type
  [context node-index]
  (let [[_ _name-token return-node _lparen _visibility _extern _lib
         align-node addrspace-node section-node callconv-node params]
        (get (:function-prototype-index context) node-index)]
    (if-not return-node
      (record-fallback! context node-index :type :function-without-return-type)
      [:fn (cond-> {}
             align-node (assoc :align (translate-expr context align-node))
             addrspace-node (assoc :addrspace (translate-expr context addrspace-node))
             section-node (assoc :linksection (translate-expr context section-node))
             callconv-node (assoc :callconv (translate-expr context callconv-node)))
       (function-type-arguments context params)
       (translate-type context return-node)])))

(defn translate-type
  "Translate one parsed Zig type node into Aguafria type data."
  [context node-index]
  (let [{:keys [tag main-token a b]} (node context node-index)
        source (node-source context node-index)]
    (cond
      (contains? (:ptr-type-index context) node-index)
      (simple-pointer-type context node-index)

      (contains? (:array-type-index context) node-index)
      (let [[_ count-node sentinel-node child-node]
            (get (:array-type-index context) node-index)]
        (if sentinel-node
          [:array-sentinel (translate-expr context count-node)
           (translate-expr context sentinel-node)
           (translate-type context child-node)]
          [:array (translate-expr context count-node)
           (translate-type context child-node)]))

      (contains? (:function-prototype-index context) node-index)
      (translate-function-type context node-index)

      (= :optional_type tag) [:optional (translate-type context a)]

      (= :error_union tag)
      (if (str/starts-with? source "!")
        [:error-union (translate-type context b)]
        [:error-union (translate-type context a) (translate-type context b)])

      (= :error_set_decl tag)
      [:error-set (error-set-members context node-index)]

      (= :identifier tag)
      (let [name (token-text context main-token)]
        (if (primitive-type? name)
          (keyword name)
          (or (declaration-reference-symbol context name) (symbol name))))

      (= :field_access tag) (translate-field context node-index)

      :else
      (let [translated (translate-expr context node-index)]
        (if (and (seq? translated) (= 'raw (first translated)))
          translated
          translated)))))

(defn- translate-local
  [context node-index]
  (let [[_ visibility extern-token _lib threadlocal comptime-token mut-token
         type-node align-node addrspace-node section-node init-node]
        (get (:var-index context) node-index)
        kind (first (token context mut-token))
        name (safe-identifier (token-text context (inc mut-token)))]
    (if (or visibility extern-token threadlocal (nil? name) (nil? init-node))
      (record-statement-fallback! context node-index :qualified-local-declaration)
      (let [operator (if (= :keyword_const kind) 'const 'var)
            value (translate-expr context init-node)
            options (cond-> {}
                      comptime-token
                      (assoc :prefix (token-text context comptime-token))
                      align-node (assoc :align (translate-expr context align-node))
                      addrspace-node
                      (assoc :addrspace (translate-expr context addrspace-node))
                      section-node
                      (assoc :linksection (translate-expr context section-node)))]
        (apply list operator name
               (concat (when (seq options) [options])
                       (when type-node [(translate-type context type-node)])
                       [value]))))))

(defn- prefixed-statement-child
  [context node-index]
  (let [{:keys [main-token last-token]} (node context node-index)
        first-child-token (inc main-token)
        capture?
        (= :pipe (first (token context first-child-token)))
        closing-pipe
        (when capture?
          (loop [token-index (inc first-child-token)]
            (let [[tag] (token context token-index)]
              (cond
                (nil? tag) nil
                (= :pipe tag) token-index
                :else (recur (inc token-index))))))
        child-token (if capture? (some-> closing-pipe inc) first-child-token)
        child-node (when child-token
                     (get (:node-span-index context) [child-token last-token]))
        capture (when (and capture? closing-pipe)
                  (some (fn [token-index]
                          (when (= :identifier (first (token context token-index)))
                            (safe-identifier (token-text context token-index))))
                        (range (inc first-child-token) closing-pipe)))]
    {:child-node child-node :capture capture :capture? capture?}))

(defn- translate-errdefer
  [context node-index]
  (let [{:keys [child-node capture capture?]}
        (prefixed-statement-child context node-index)]
    (if (and child-node (or (not capture?) capture))
      (if capture
        (list 'errdefer [capture] (translate-stmt context child-node))
        (list 'errdefer (translate-stmt context child-node)))
      (record-statement-fallback! context node-index :qualified-errdefer))))

(defn- translate-while
  [context node-index]
  (let [[_ condition continue-node then-node else-node payload-token error-token]
        (get (:while-index context) node-index)
        {:keys [first-token main-token]} (node context node-index)
        label (when (and (< first-token main-token)
                         (= :identifier (first (token context first-token)))
                         (= :colon (first (token context (inc first-token)))))
                (safe-identifier (token-text context first-token)))
        qualifier-token (cond-> first-token label (+ 2))
        inline? (= :keyword_inline (first (token context qualifier-token)))
        qualifier-end (cond-> qualifier-token inline? inc)
        qualified? (not= qualifier-end main-token)
        payload (capture-forms context payload-token)
        error (capture-forms context error-token)]
    (if qualified?
      (record-statement-fallback! context node-index :qualified-while)
      (let [body (if (contains? (:block-index context) then-node)
                   (rest (translate-block context then-node))
                   [(translate-stmt context then-node)])
            else-body (when else-node
                        (if (contains? (:block-index context) else-node)
                          (vec (rest (translate-block context else-node)))
                          [(translate-stmt context else-node)]))
            options (cond-> {}
                      label (assoc :label label)
                      inline? (assoc :inline? true)
                      (seq payload) (assoc :payload payload)
                      continue-node
                      (assoc :continue (translate-expr context continue-node))
                      (seq error) (assoc :error error)
                      else-node (assoc :else else-body))]
        (apply list 'while-loop options
               (translate-expr context condition) body)))))

(defn- capture-forms
  [context payload-token]
  (if-not payload-token
    []
    (loop [token-index payload-token
           captures []]
      (let [[tag] (token context token-index)]
        (cond
          (or (nil? tag) (= :pipe tag)) captures
          (= :asterisk tag)
          (let [name-token (inc token-index)
                identifier (when (= :identifier (first (token context name-token)))
                             (safe-identifier (token-text context name-token)))]
            (when identifier
              (recur (inc name-token)
                     (conj captures (list 'pointer-capture identifier)))))
          (= :identifier tag)
          (if-let [identifier (safe-identifier (token-text context token-index))]
            (recur (inc token-index) (conj captures identifier))
            nil)
          :else (recur (inc token-index) captures))))))

(defn- translate-switch-target
  [context node-index]
  (let [{:keys [tag]} (node context node-index)]
    (cond
      (contains? (:block-index context) node-index)
      (translate-block context node-index)

      (contains? #{:return :break :continue} tag)
      (translate-stmt context node-index)

      :else
      (translate-expr context node-index))))

(defn- translate-switch-case
  [context node-index]
  (let [[_ value-nodes target-node payload-token inline-token]
        (get (:switch-case-index context) node-index)
        values (mapv #(translate-expr context %) value-nodes)
        captures (capture-forms context payload-token)
        target (translate-switch-target context target-node)
        operator (cond
                   (and (empty? values) inline-token) 'inline-case-else
                   (empty? values) 'case-else
                   inline-token 'inline-case
                   :else 'case)]
    (if (empty? values)
      (if (seq captures)
        (list operator captures target)
        (list operator target))
      (if (seq captures)
        (list operator values captures target)
        (list operator values target)))))

(defn- translate-switch
  ([context node-index] (translate-switch context node-index false))
  ([context node-index statement?]
   (let [[_ condition-node case-nodes label-token]
         (get (:switch-index context) node-index)
         label (when label-token
                 (safe-identifier (token-text context label-token)))
         clauses (map #(translate-switch-case context %) case-nodes)]
     (if (and label-token (nil? label))
       (record-fallback! context node-index :expression :quoted-switch-label)
       (apply list
              (cond
                (and label statement?) 'labeled-switch-stmt
                label 'labeled-switch
                statement? 'switch-stmt
                :else 'switch)
              (concat (when label [label])
                      [(translate-expr context condition-node)]
                      clauses))))))

(def ^:private nested-declaration-operators
  {'az/defn 'fn-decl
   'az/defconst 'const-decl
   'az/defvar 'var-decl
   'az/defstruct 'struct-decl
   'az/defimport 'import-decl
   'az/deffield 'field-decl
   'az/defcomptime 'comptime-decl
   'az/deftest 'test-decl
   'az/defextern 'fn-proto-decl})

(defn- nested-declaration-form
  [form]
  (if-let [operator (get nested-declaration-operators (first form))]
    (with-meta (apply list operator (rest form)) (meta form))
    form))

(defn- node-end-after-separator
  [context node-index]
  (let [{:keys [last-token]} (node context node-index)
        following (inc last-token)]
    (if (contains? #{:semicolon :comma} (first (token context following)))
      (token-end context following)
      (second (node-range context node-index)))))

(defn- container-opening-token
  [context node-index members]
  (let [{:keys [main-token last-token]} (node context node-index)
        limit (if-let [member (first members)]
                (:first-token (node context member))
                last-token)]
    (some (fn [token-index]
            (when (= :l_brace (first (token context token-index))) token-index))
          (range main-token (inc limit)))))

(defn- nested-field-form
  [context container-kind node-index order leading]
  (let [[_ comptime-token field-token type-node align-node value-node tuple-like?]
        (get (:container-field-index context) node-index)
        zig-name (token-text context field-token)
        name (or (safe-identifier zig-name) (symbol (str "zig-field-" order)))
        attributes (cond-> {:export false
                            :public false
                            :source-comment false
                            :zig/order order
                            :zig/leading leading}
                     (not= (str name) zig-name) (assoc :zig/name zig-name)
                     comptime-token (assoc :zig/prefix
                                           (token-text context comptime-token))
                     align-node (assoc :zig/align
                                       (translate-expr context align-node)))
        value (when value-node (translate-expr context value-node))]
    (cond
      (and (contains? #{:enum :union} container-kind)
           (or (nil? type-node) tuple-like?))
      (apply list 'enum-field-decl name attributes
             (when value-node [value]))

      tuple-like?
      (apply list 'tuple-field-decl attributes
             (concat [(translate-type context type-node)]
                     (when value-node [value])))

      type-node
      (apply list 'field-decl name attributes
             (concat [(translate-type context type-node)]
                     (when value-node [value])))

      :else
      (record-fallback! context node-index :expression
                        :unsupported-container-field))))

(defn- translate-container-member
  [context container-kind node-index order leading]
  (let [tag (:tag (node context node-index))
        form
        (cond
          (contains? (:container-field-index context) node-index)
          (nested-field-form context container-kind node-index order leading)

          (= :fn_decl tag)
          (translate-function-declaration context node-index order leading)

          (contains? (:var-index context) node-index)
          (translate-var-declaration context node-index order leading)

          (= :test_decl tag)
          (translate-test-declaration context node-index order leading)

          (= :comptime tag)
          (translate-comptime-declaration context node-index order leading)

          (contains? (:function-prototype-index context) node-index)
          (translate-function-prototype context node-index order leading)

          :else
          (record-fallback! context node-index :expression
                            (keyword (str "unsupported-container-member-"
                                          (name tag)))))]
    (nested-declaration-form form)))

(defn- translate-container
  [context node-index]
  (let [[_ layout-token main-token enum-token members argument-node]
        (get (:container-index context) node-index)
        kind (keyword (token-text context main-token))
        opening-token (container-opening-token context node-index members)
        opening-end (when opening-token (token-end context opening-token))
        translated
        (loop [remaining members previous nil order 0 forms []]
          (if-let [member (first remaining)]
            (let [start (if previous
                          (node-end-after-separator context previous)
                          opening-end)
                  end (first (node-range context member))
                  leading (if (and start end) (byte-slice (:source-bytes context)
                                                         start end) "")]
              (recur (next remaining) member (inc order)
                     (conj forms (translate-container-member
                                  context kind member order leading))))
            forms))
        trailing-start (if-let [member (last members)]
                         (node-end-after-separator context member)
                         opening-end)
        trailing-end (token-start context (:last-token (node context node-index)))
        trailing (if (and trailing-start trailing-end)
                   (byte-slice (:source-bytes context) trailing-start trailing-end)
                   "")
        options (cond-> {:kind kind
                         :layout (case (when layout-token
                                         (token-text context layout-token))
                                   "extern" :extern
                                   "packed" :packed
                                   :normal)
                         :enum? (boolean enum-token)
                         :zig/trailing trailing}
                  argument-node
                  (assoc :argument (translate-type context argument-node)))]
    (apply list 'container options translated)))

(defn- translate-for
  [context node-index]
  (let [[_ inputs then-node else-node payload-token]
        (get (:for-index context) node-index)
        captures (capture-forms context payload-token)
        {:keys [first-token main-token]} (node context node-index)
        label (when (and (< first-token main-token)
                         (= :identifier (first (token context first-token)))
                         (= :colon (first (token context (inc first-token)))))
                (safe-identifier (token-text context first-token)))
        qualifier-token (cond-> first-token label (+ 2))
        inline? (= :keyword_inline (first (token context qualifier-token)))
        qualifier-end (cond-> qualifier-token inline? inc)
        qualified? (not= qualifier-end main-token)]
    (if (or qualified? (empty? inputs)
            (not= (count inputs) (count captures)))
      (record-statement-fallback! context node-index :qualified-for)
      (let [body (if (contains? (:block-index context) then-node)
                   (rest (translate-block context then-node))
                   [(translate-stmt context then-node)])
            else-form (when else-node
                        (if (contains? (:block-index context) else-node)
                          (apply list 'else-clause
                                 (rest (translate-block context else-node)))
                          (list 'else-expression
                                (translate-expr context else-node))))
            bindings (mapv (fn [capture input]
                             [capture (translate-expr context input)])
                           captures inputs)
            operator (cond
                       label 'for-loop
                       inline? 'inline-for
                       :else 'for)
            prefix-arguments (when label [{:label label :inline? inline?}])]
        (apply list operator
               (concat prefix-arguments [bindings] body
                       (when else-form [else-form])))))))

(defn translate-stmt
  "Translate one statement node, retaining an explicit raw boundary when the
  high-level emitter does not model that statement yet."
  [context node-index]
  (let [{:keys [tag a b]} (node context node-index)]
    (cond
      (contains? (:var-index context) node-index)
      (translate-local context node-index)

      (contains? assignment-tokens tag)
      (let [operator (get assignment-tokens tag)
            target (translate-expr context a)
            value (translate-expr context b)]
        (cond
          (= "=" operator) (list 'set! target value)
          (contains? simple-assignment-symbols operator)
          (list (get simple-assignment-symbols operator) target value)
          :else (list 'assign operator target value)))

      (= :return tag)
      (if a (list 'return (translate-expr context a)) (list 'return))

      (= :defer tag) (list 'defer (translate-stmt context a))
      (= :errdefer tag) (translate-errdefer context node-index)
      (= :comptime tag) (list 'comptime-stmt (translate-stmt context a))
      (= :if tag) (translate-if context node-index true)
      (= :if_simple tag) (translate-if context node-index true)
      (contains? (:while-index context) node-index) (translate-while context node-index)
      (contains? (:for-index context) node-index) (translate-for context node-index)
      (contains? (:switch-index context) node-index)
      (translate-switch context node-index true)
      (contains? (:block-index context) node-index) (translate-block context node-index)

      (= :break tag)
      (let [{:keys [first-token last-token]} (node context node-index)
            next-token (inc first-token)]
        (cond
          (> next-token last-token)
          (list 'break)

          (= :colon (first (token context next-token)))
          (let [label-token (inc next-token)
                value-token (inc label-token)
                label (safe-identifier (token-text context label-token))]
            (if (<= value-token last-token)
              (if-let [value-node (get (:node-span-index context)
                                       [value-token last-token])]
                (list 'break label (translate-expr context value-node))
                (record-statement-fallback! context node-index
                                            :qualified-break-value))
              (list 'break-label label)))

          :else
          (if-let [value-node (get (:node-span-index context)
                                   [next-token last-token])]
            (list 'break (translate-expr context value-node))
            (record-statement-fallback! context node-index :break-value))))

      (= :continue tag)
      (let [{:keys [first-token last-token]} (node context node-index)
            colon-token (inc first-token)]
        (cond
          (> colon-token last-token) (list 'continue)
          (= :colon (first (token context colon-token)))
          (list 'continue
                (safe-identifier (token-text context (inc colon-token))))
          :else
          (record-statement-fallback! context node-index :qualified-continue)))

      :else
      (let [expression (translate-expr context node-index)]
        (if (and (seq? expression) (= 'raw (first expression)))
          (record-statement-fallback! context node-index
                                      (keyword (str "unsupported-" (name tag))))
          expression)))))

(defn translate-block
  [context node-index]
  (if-let [[_ statements] (get (:block-index context) node-index)]
    (let [{:keys [first-token main-token]} (node context node-index)
          labeled? (< first-token main-token)
          label (when labeled?
                  (safe-identifier (token-text context first-token)))]
      (apply list
             (concat [(if labeled? 'labeled-block 'block)]
                     (when labeled? [label])
                     (map #(translate-stmt context %) statements))))
    (record-statement-fallback! context node-index :non-block-body)))

(defn- words-contain?
  [source word]
  (boolean (re-find (re-pattern (str "(?:^|\\s)" (java.util.regex.Pattern/quote word)
                                     "(?:$|\\s)"))
                    source)))

(defn- docstring-from-leading
  [leading]
  (let [lines (->> (str/split-lines leading)
                   (keep #(second (re-matches #"\s*/// ?(.*)" %))))]
    (when (seq lines) (str/join "\n" lines))))

(defn- declaration-name
  [context zig-name options]
  (let [clojure-name (or (get (:declaration-names context) zig-name)
                         (safe-identifier zig-name))]
    (with-meta clojure-name
    (into {}
          (remove (comp nil? val))
          (cond-> options
            (not= (str clojure-name) zig-name) (assoc :zig/name zig-name))))))

(defn- declaration-name-and-attributes
  [context zig-name options]
  (let [clojure-name (or (get (:declaration-names context) zig-name)
                         (safe-identifier zig-name))]
    [clojure-name
     (into {}
           (remove (comp nil? val))
           (cond-> options
             (not= (str clojure-name) zig-name) (assoc :zig/name zig-name)))]))

(defn- prefix-before-token
  [context node-index token-index]
  (let [[start _] (node-range context node-index)]
    (str/trim (byte-slice (:source-bytes context) start
                          (token-start context token-index)))))

(defn- matching-rparen
  [context lparen]
  (loop [token-index lparen
         depth 0]
    (let [[tag] (token context token-index)
          depth (case tag :l_paren (inc depth) :r_paren (dec depth) depth)]
      (if (and (= :r_paren tag) (zero? depth))
        token-index
        (recur (inc token-index) depth)))))

(defn- function-qualifiers
  [context lparen return-node]
  (let [rparen (matching-rparen context lparen)
        start (token-end context rparen)
        end (first (node-range context return-node))]
    (str/trim (byte-slice (:source-bytes context) start end))))

(defn- function-arguments
  [context params]
  (mapv
   (fn [[name-token type-node qualifier-token anytype-token]]
     (let [special (when anytype-token (token-text context anytype-token))]
       (if (= "..." special)
         ['... {:zig/variadic true} '_]
         (let [name (or (some->> name-token (token-text context) safe-identifier) '_)
               type (cond
                      type-node (translate-type context type-node)
                      (= "anytype" special) :anytype
                      :else (raw-form (or special "anytype")))
               properties (cond-> {}
                            qualifier-token
                            (assoc :zig/prefix (token-text context qualifier-token)))]
           (if (seq properties) [name properties type] [name type])))))
   params))

(defn- translate-function-declaration
  [context node-index order leading]
  (let [[_ proto-node body-node name-token return-node lparen _visibility _extern _lib
         _align _addrspace _section _callconv params]
        (get (:function-index context) node-index)
        zig-name (when name-token (token-text context name-token))
        name (when zig-name (or (get (:declaration-names context) zig-name)
                                (safe-identifier zig-name)))]
    (if-not (and name return-node body-node)
      nil
      (let [prefix (prefix-before-token context node-index
                                        (:main-token (node context proto-node)))
            qualifiers (function-qualifiers context lparen return-node)
            metadata {:export (words-contain? prefix "export")
                      :public (or (words-contain? prefix "pub")
                                  (words-contain? prefix "export"))
                      :implicit-return false
                      :source-comment false
                      :zig/order order
                      :zig/leading leading
                      :zig/prefix prefix
                      :zig/qualifiers (when (seq qualifiers) qualifiers)}
            [declaration-name attributes]
            (declaration-name-and-attributes context zig-name metadata)
            return (translate-type context return-node)
            bindings (function-arguments context params)
            body (if (contains? (:block-index context) body-node)
                   (rest (translate-block context body-node))
                   [(record-statement-fallback! context body-node :non-block-function-body)])
            docstring (docstring-from-leading leading)]
        (apply list 'az/defn declaration-name
               (concat (when docstring [docstring])
                       [attributes ':- return bindings]
                       body))))))

(defn- token-before-tag
  [context start-token wanted]
  (loop [token-index (dec start-token)]
    (when (not (neg? token-index))
      (if (= wanted (first (token context token-index)))
        token-index
        (recur (dec token-index))))))

(defn- variable-qualifiers
  [context name-token type-node init-node]
  (let [equal-token (token-before-tag context
                                      (:first-token (node context init-node))
                                      :equal)
        start (if type-node
                (second (node-range context type-node))
                (token-end context name-token))]
    (when equal-token
      (let [source (str/trim
                    (byte-slice (:source-bytes context) start
                                (token-start context equal-token)))]
        (when (seq source) source)))))

(defn- import-initializer
  [context init-node]
  (when-let [[_ arguments] (get (:builtin-index context) init-node)]
    (when (and (= "@import" (token-text context
                                        (:main-token (node context init-node))))
               (= 1 (count arguments)))
      (parse-string (node-source context (first arguments))))))

(defn- simple-struct-fields
  [context container-node]
  (let [[_ layout-token main-token enum-token members argument]
        (get (:container-index context) container-node)]
    (when (and (= "struct" (token-text context main-token))
               (nil? enum-token) (nil? argument))
      (let [fields
            (mapv
             (fn [member]
               (when-let [[_ comptime-token field-token type-node align-node value-node
                           tuple-like?]
                          (get (:container-field-index context) member)]
                 (when (and (nil? comptime-token) type-node (nil? align-node)
                            (nil? value-node) (not tuple-like?))
                   (when-let [field (safe-identifier (token-text context field-token))]
                     [(keyword (name field)) (translate-type context type-node)]))))
             members)]
        (when (every? some? fields)
          {:layout (case (when layout-token (token-text context layout-token))
                     "extern" :extern
                     "packed" :packed
                     :normal)
           :fields fields})))))

(defn- translate-var-declaration
  [context node-index order leading]
  (let [[_ _visibility _extern _lib _threadlocal _comptime mut-token type-node
         _align _addrspace _section init-node]
        (get (:var-index context) node-index)
        name-token (inc mut-token)
        zig-name (token-text context name-token)
        name (or (get (:declaration-names context) zig-name)
                 (safe-identifier zig-name))
        prefix (prefix-before-token context node-index mut-token)]
    (when (and name init-node)
      (let [metadata {:export false
                      :public (words-contain? prefix "pub")
                      :source-comment false
                      :zig/order order
                      :zig/leading leading
                      :zig/prefix prefix}
            [declaration-name attributes]
            (declaration-name-and-attributes context zig-name metadata)
            docstring (docstring-from-leading leading)
            import-name (import-initializer context init-node)
            simple-struct
            (and (= :keyword_const (first (token context mut-token)))
                 (contains? (:container-index context) init-node)
                 (simple-struct-fields context init-node))]
        (cond
          import-name
          (if-let [{:keys [alias namespace]}
                   (get (:import-bindings context) zig-name)]
            (do
              (swap! (:project-aliases context) assoc namespace alias)
              ::omit-declaration)
            (if (= "std" import-name)
              ::omit-declaration
              ;; Preserve compiler/build-provided imports as ordinary,
              ;; inspectable module-valued Vars. `az/defimport` is a convenience
              ;; for manually exposing selected external members; a converted
              ;; Zig declaration is exactly a const initialized by @import.
              (apply list 'az/defconst declaration-name
                     (concat (when docstring [docstring])
                             [attributes (translate-expr context init-node)]))))

          simple-struct
          (let [{:keys [layout fields]} simple-struct]
            (apply list 'az/defstruct declaration-name
                   (concat (when docstring [docstring])
                           [(cond-> attributes layout (assoc :layout layout))
                            fields])))

          :else
          (let [kind (if (= :keyword_const (first (token context mut-token)))
                       'az/defconst 'az/defvar)
                type (when type-node (translate-type context type-node))
                qualifiers (variable-qualifiers context name-token type-node init-node)
                attributes (cond-> attributes
                             qualifiers (assoc :zig/qualifiers qualifiers))]
            (apply list kind declaration-name
                   (concat (when docstring [docstring])
                           [attributes]
                           (when type [type])
                           [(translate-expr context init-node)]))))))))

(defn- translate-test-declaration
  [context node-index order leading]
  (let [[_ name-token body-node] (get (:test-index context) node-index)
        test-name (when name-token
                    (let [source (token-text context name-token)]
                      (or (parse-string source) (safe-identifier source))))
        body (if (contains? (:block-index context) body-node)
               (rest (translate-block context body-node))
               [(record-statement-fallback! context body-node :non-block-test-body)])]
    (apply list 'az/deftest
           {:zig/order order :zig/leading leading :source-comment false}
           test-name body)))

(defn- translate-comptime-declaration
  [context node-index order leading]
  (let [body-node (:a (node context node-index))
        body (if (contains? (:block-index context) body-node)
               (rest (translate-block context body-node))
               [(translate-stmt context body-node)])
        name (with-meta (symbol (str "zig-comptime-" order))
               {:export false :public false :source-comment false
                :zig/order order :zig/leading leading})]
    (apply list 'az/defcomptime name body)))

(defn- translate-container-field-declaration
  [context node-index order leading]
  (let [[_ comptime-token field-token type-node align-node value-node tuple-like?]
        (get (:container-field-index context) node-index)
        zig-name (token-text context field-token)]
    (when (or tuple-like? (nil? type-node) (nil? (safe-identifier zig-name)))
      (throw (ex-info "Aguafria cannot structurally convert this Zig root field"
                      {:path (:path context)
                       :node node-index
                       :tag (:tag (node context node-index))
                       :source (node-source context node-index)})))
    (let [metadata {:export false
                    :public false
                    :source-comment false
                    :zig/order order
                    :zig/leading leading
                    :zig/prefix (when comptime-token
                                  (token-text context comptime-token))
                    :zig/align (when align-node
                                 (translate-expr context align-node))}
          decorated (declaration-name context zig-name metadata)
          form ['az/deffield decorated (translate-type context type-node)]]
      (apply list (cond-> form
                    value-node (conj (translate-expr context value-node)))))))

(defn- translate-function-prototype
  [context node-index order leading]
  (let [[_ name-token return-node lparen _visibility _extern _lib
         _align _addrspace _section _callconv params]
        (get (:function-prototype-index context) node-index)
        zig-name (when name-token (token-text context name-token))]
    (when-not (and zig-name return-node)
      (throw (ex-info "Aguafria cannot structurally convert this Zig function prototype"
                      {:path (:path context)
                       :node node-index
                       :source (node-source context node-index)})))
    (let [prefix (prefix-before-token context node-index
                                      (:main-token (node context node-index)))
          qualifiers (function-qualifiers context lparen return-node)
          metadata {:export false
                    :public (words-contain? prefix "pub")
                    :source-comment false
                    :zig/order order
                    :zig/leading leading
                    :zig/prefix prefix
                    :zig/qualifiers (when (seq qualifiers) qualifiers)}
          decorated (declaration-name context zig-name metadata)]
      (list 'az/defextern decorated ':-
            (translate-type context return-node)
            (function-arguments context params)))))

(defn- unsupported-top-level!
  [context node-index]
  (let [[start end] (node-range context node-index)]
    (throw (ex-info "Aguafria has no structural form for this Zig root declaration"
                    {:aguafria/phase :zig-conversion
                     :path (:path context)
                     :node node-index
                     :tag (:tag (node context node-index))
                     :start-byte start
                     :end-byte end
                     :source (node-source context node-index)}))))

(defn- leading-source
  [context previous-node node-index]
  (let [start (if previous-node
                (let [{:keys [last-token]} (node context previous-node)
                      following (inc last-token)]
                  (if (contains? #{:semicolon :comma}
                                 (first (token context following)))
                    (token-end context following)
                    (second (node-range context previous-node))))
                0)
        end (first (node-range context node-index))]
    (byte-slice (:source-bytes context) start end)))

(defn- translate-declarations
  [context]
  (loop [remaining (:root-decls context)
         previous nil
         order 0
         forms []]
    (if-let [node-index (first remaining)]
      (let [leading (leading-source context previous node-index)
            tag (:tag (node context node-index))
            form (cond
                   (= :fn_decl tag)
                   (translate-function-declaration context node-index order leading)

                   (contains? (:var-index context) node-index)
                   (translate-var-declaration context node-index order leading)

                   (= :test_decl tag)
                   (translate-test-declaration context node-index order leading)

                   (= :comptime tag)
                   (translate-comptime-declaration context node-index order leading)

                   (contains? (:container-field-index context) node-index)
                   (translate-container-field-declaration context node-index order leading)

                   (contains? (:function-prototype-index context) node-index)
                   (translate-function-prototype context node-index order leading)

                   :else nil)
            form (or form
                     (unsupported-top-level! context node-index))]
        (recur (next remaining) node-index (inc order)
               (cond-> forms
                 (not= ::omit-declaration form) (conj form))))
      forms)))

(defn- pprint-code
  [form]
  (binding [pprint/*print-right-margin* 100
            pprint/*print-miser-width* 60
            *print-namespace-maps* false
            *print-meta* true]
    (with-out-str
      (pprint/write form :dispatch pprint/code-dispatch))))

(defn- require-specs
  [std-aliases project-aliases]
  (let [root 'aguafria.std
        root-alias (get std-aliases root)
        nested (dissoc std-aliases root)]
    (vec
     (concat
      [(if root-alias [root :as root-alias] root)]
      (map (fn [[namespace-symbol alias]] [namespace-symbol :as alias])
           (sort-by (comp str key) nested))
      ;; `:as-alias` gives converted Zig modules ordinary Clojure aliases
      ;; without recursively loading a dependency while a source-only project
      ;; batch is being collected. This also permits Zig import cycles, which
      ;; ordinary eager Clojure `:require` cannot represent.
      (map (fn [[namespace-symbol alias]] [namespace-symbol :as-alias alias])
           (sort-by (comp str key) project-aliases))
      [['aguafria.keyword :as 'ak]
       ['aguafria.zig :as 'az]]))))

(defn- namespace-form
  [namespace-symbol std-aliases project-aliases project-imports]
  (apply list
         (concat ['ns namespace-symbol]
                 (when (seq project-imports)
                   [{:aguafria/zig-imports project-imports}])
                 [(list* :require (require-specs std-aliases project-aliases))])))

(defn- used-project-imports
  [context project-aliases]
  (into (sorted-map)
        (map (fn [[namespace-symbol alias]]
               (let [{:keys [import-name declarations]}
                     (some (fn [[_ binding]]
                             (when (and (= alias (:alias binding))
                                        (= namespace-symbol (:namespace binding)))
                               binding))
                           (:import-bindings context))]
                 [(str alias) {:namespace (str namespace-symbol)
                               :import-name import-name
                               :declarations
                               (into (sorted-map)
                                     (map (fn [[zig-name clojure-name]]
                                            [(str clojure-name) zig-name]))
                                     declarations)}]))
        project-aliases)))

(defn- fallback-view
  [fallbacks]
  (->> fallbacks
       (reduce (fn [result fallback]
                 (assoc result [(:node fallback) (:role fallback)] fallback))
               {})
       vals
       (sort-by (juxt :start-byte :node :role))
       vec))

(defn- top-level-name-token
  [parsed node-index]
  (cond
    (contains? (:function-index parsed) node-index)
    (nth (get (:function-index parsed) node-index) 3 nil)

    (contains? (:function-prototype-index parsed) node-index)
    (nth (get (:function-prototype-index parsed) node-index) 1 nil)

    (contains? (:var-index parsed) node-index)
    (some-> (nth (get (:var-index parsed) node-index) 6 nil) inc)

    (contains? (:container-field-index parsed) node-index)
    (nth (get (:container-field-index parsed) node-index) 2 nil)

    :else nil))

(defn- clojure-name-occupied?
  [candidate]
  (or (special-symbol? candidate)
      (some? (get (ns-map (the-ns 'aguafria.zig.convert)) candidate))))

(defn- declaration-name-map
  [parsed]
  (let [zig-names (->> (:root-decls parsed)
                       (keep #(some->> (top-level-name-token parsed %)
                                      (token-text parsed)))
                       (filter safe-identifier)
                       distinct
                       vec)
        occupied-zig (set zig-names)]
    (first
     (reduce
      (fn [[mapping used] zig-name]
        (let [plain (symbol zig-name)
              clojure-name
              (if-not (clojure-name-occupied? plain)
                plain
                (loop [suffix "-zig" index 2]
                  (let [candidate (symbol (str zig-name suffix))]
                    (if (or (contains? occupied-zig (str candidate))
                            (contains? used candidate)
                            (clojure-name-occupied? candidate))
                      (recur (str "-zig-" index) (inc index))
                      candidate))))]
          [(assoc mapping zig-name clojure-name) (conj used clojure-name)]))
      [{} #{}]
      zig-names))))

(defn- conversion-context
  [parsed options]
  (assoc parsed
         :fallbacks (atom [])
         :std-aliases (atom {})
         :project-aliases (atom {})
         :import-bindings (or (:import-bindings options) {})
         :declaration-names (declaration-name-map parsed)))

(defn- conversion-report
  [context namespace-symbol forms elapsed-ms]
  (let [fallbacks (fallback-view @(:fallbacks context))
        kinds (frequencies (map first forms))]
    {:path (:path context)
     :namespace namespace-symbol
     :zig-version (:zig-version context)
     :source-bytes (alength ^bytes (:source-bytes context))
     :token-count (count (:tokens context))
     :node-count (count (:nodes context))
     :declaration-count (count forms)
     :declarations-by-api (into (sorted-map)
                                (map (fn [[kind count]] [(str kind) count]))
                                kinds)
     :structural-declaration-count (- (count forms)
                                      (get kinds 'az/defraw 0))
     :raw-declaration-count (get kinds 'az/defraw 0)
     :fallback-count (count fallbacks)
     :fallbacks fallbacks
     :std-namespaces (->> @(:std-aliases context) keys sort vec)
     :project-namespaces (->> @(:project-aliases context) keys sort vec)
     :elapsed-ms elapsed-ms}))

(defn convert-file
  "Convert a Zig file and return its formatted Clojure namespace plus report.

  Options include `:namespace`, `:zig`, and `:cache-dir`. No file is written."
  ([path] (convert-file path {}))
  ([path {:keys [namespace source-display-path] :as options}]
   (let [started (System/nanoTime)
         parsed (or (::parsed options) (parse-file path options))
         context (conversion-context parsed options)
         namespace-symbol (or (some-> namespace symbol)
                              (symbol "zig.converted"
                                      (-> (io/file path) .getName
                                          (str/replace #"\.zig$" "")
                                          (str/replace "_" "-"))))
         forms (translate-declarations context)
         aliases @(:std-aliases context)
         project-aliases @(:project-aliases context)
         project-imports (used-project-imports context project-aliases)
         clojure-source
         (str ";; Generated from " (or source-display-path (str path))
              " by Aguafria.\n"
              ";; Edit and reevaluate these ordinary declarations at the REPL.\n\n"
              (pprint-code (namespace-form namespace-symbol aliases
                                           project-aliases project-imports))
              "\n"
              (str/join "\n" (map pprint-code forms))
              (when (seq forms) "\n")
              "")
         report (conversion-report context namespace-symbol forms
                                   (/ (- (System/nanoTime) started) 1e6))]
     (when (pos? (:fallback-count report))
       (throw
        (ex-info
         (str "Aguafria cannot structurally convert " (:fallback-count report)
              " Zig AST node" (when (not= 1 (:fallback-count report)) "s")
              "; no raw Zig was generated")
         {:path (str path)
          :namespace namespace-symbol
          :fallback-count (:fallback-count report)
          :fallbacks (:fallbacks report)
          :report report
          :hint "Add structural converter/emitter support for every reported AST node."})))
     {:namespace namespace-symbol
      :forms forms
      :std-aliases aliases
      :project-aliases project-aliases
      :project-imports project-imports
      :clojure-source clojure-source
      :report report})))

(defn- remove-conversion-namespaces!
  [scratch-symbol]
  (let [scratch (str scratch-symbol)
        import-prefix (str "aguafria.zig.import." scratch ".")]
    (doseq [namespace (all-ns)
            :let [name (str (ns-name namespace))]
            :when (or (= scratch name) (str/starts-with? name import-prefix))]
      (remove-ns (ns-name namespace)))))

(defn- evaluate-converted-forms
  [namespace-symbol std-aliases project-aliases project-imports forms]
  (let [scratch-symbol (symbol (str "aguafria.zig.convert.scratch.n"
                                    (Math/abs (long (hash (str namespace-symbol
                                                               (gensym)))))))
        scratch (create-ns scratch-symbol)
        collector (atom [])]
    (try
      (binding [*ns* scratch
                *file* (str namespace-symbol)]
        (alter-meta! scratch assoc :aguafria/zig-imports project-imports)
        (refer 'clojure.core)
        (doseq [require-spec (require-specs std-aliases project-aliases)]
          (require require-spec))
        ;; Each declaration is evaluated as its own top-level form. Besides
        ;; matching REPL behavior, this avoids the JVM method-size limit for a
        ;; very large raw Zig declaration.
        (binding [runtime/*registration-batch* collector]
          (doseq [form forms]
            (eval form))))
      (let [declarations @collector]
        ;; Emit while scratch Vars still exist: qualified declaration/import
        ;; references resolve through their metadata, then the namespace can be
        ;; removed without leaking tooling state into the user's REPL.
        {:declarations declarations
         :zig-source (emitter/emit-module (str namespace-symbol) declarations)})
      (finally
        (remove-conversion-namespaces! scratch-symbol)))))

(defn render-zig
  "Convert `path` and render the resulting ordinary Aguafria declarations
  back to a complete Zig source module without registering it globally.

  Returns `:zig-source`, the formatted Clojure namespace, declarations, and
  the conversion report. This is useful for verification and migration tools."
  ([path] (render-zig path {}))
  ([path options]
   (let [{:keys [namespace forms std-aliases project-aliases project-imports]
          :as converted}
         (convert-file path options)
         {:keys [declarations zig-source]}
         (evaluate-converted-forms namespace std-aliases project-aliases
                                   project-imports forms)]
     (assoc converted
            :declarations declarations
            :zig-source zig-source))))

(defn- absolute-path
  [path]
  (.getAbsolutePath (io/file path)))

(defn- root-module-arguments
  [source-file {:keys [optimize target cpu zig-args modules]
                :or {optimize "Debug" zig-args [] modules {}}}]
  (let [modules (sort-by (comp str key) modules)]
    (vec
     (concat
      [(str "-O" optimize)]
      (when target ["-target" (str target)])
      (when cpu ["-mcpu" (str cpu)])
      (mapcat (fn [[module-name _]]
                ["--dep" (if (instance? clojure.lang.Named module-name)
                           (name module-name)
                           (str module-name))])
              modules)
      zig-args
      [(str "-Mroot=" (absolute-path source-file))]
      (map (fn [[module-name module-path]]
             (str "-M"
                  (if (instance? clojure.lang.Named module-name)
                    (name module-name)
                    (str module-name))
                  "=" (absolute-path module-path)))
           modules)))))

(defn- formatted-zig
  [zig source directory]
  (let [result (run-command-input [zig "fmt" "--stdin"] directory source)]
    (when (zero? (:exit result)) (:out result))))

(defn verify-file
  "Round-trip one Zig file through Aguafria and ask Zig to verify the result.

  `:mode` is `:ast-check` (the default), `:test`, or `:build-obj`. Compiler
  options include `:zig`, `:cache-dir`, `:optimize`, `:target`, `:cpu`,
  `:zig-args`, and `:modules`. The report also records whether Zig's formatter
  produces text-identical sources; compiler success is authoritative because
  structural std Vars and harmless parentheses can intentionally change text.
  Failures throw by default; pass `:throw? false` to receive the failed report."
  ([path] (verify-file path {}))
  ([path {:keys [mode zig cache-dir throw?]
          :or {mode :ast-check zig "zig" cache-dir ".aguafria/zig" throw? true}
          :as options}]
   (when-not (contains? #{:ast-check :test :build-obj} mode)
     (throw (ex-info "Unsupported Zig conversion verification mode"
                     {:mode mode :supported [:ast-check :test :build-obj]})))
   (let [{:keys [zig-source report] :as rendered} (render-zig path options)
         hash (subs (sha256 [zig-source (:zig-version report) mode
                             (select-keys options [:optimize :target :cpu
                                                   :zig-args :modules])])
                    0 24)
         directory (.getCanonicalFile (io/file cache-dir "conversion" "verify" hash))
         source-file (io/file directory "converted.zig")
         _ (.mkdirs directory)
         _ (Files/writeString (.toPath source-file) zig-source StandardCharsets/UTF_8
                              (into-array StandardOpenOption
                                          [StandardOpenOption/CREATE
                                           StandardOpenOption/TRUNCATE_EXISTING
                                           StandardOpenOption/WRITE]))
         command (case mode
                   :ast-check [zig "ast-check" (.getAbsolutePath source-file)]
                   :test (vec (concat [zig "test"]
                                      (root-module-arguments source-file options)))
                   :build-obj
                   (vec (concat [zig "build-obj"]
                                (root-module-arguments source-file options))))
         result (run-command command (.getAbsolutePath directory))
         original-formatted (formatted-zig zig (:source (parse-file path options))
                                           (.getAbsolutePath directory))
         rendered-without-header
         (str/replace-first zig-source
                            #"(?s)^// Generated by Aguafria\..*?// Module:.*?\n\n"
                            "")
         rendered-formatted (formatted-zig zig rendered-without-header
                                           (.getAbsolutePath directory))
         verification (merge
                       {:path (:path report)
                        :namespace (:namespace report)
                        :mode mode
                        :success? (zero? (:exit result))
                        :zig-version (:zig-version report)
                        :source-path (.getAbsolutePath source-file)
                        :formatted-source-equal?
                        (and original-formatted rendered-formatted
                             (= original-formatted rendered-formatted))
                        :fallback-count (:fallback-count report)
                        :command (:command result)
                        :directory (:directory result)
                        :exit (:exit result)
                        :stdout (:out result)
                        :stderr (:err result)}
                       (select-keys report [:declaration-count
                                            :structural-declaration-count
                                            :raw-declaration-count]))]
     (record-conversion! verification)
     (when (and throw? (not (:success? verification)))
       (throw (ex-info
               (str "Round-tripped Zig verification failed for " path "\n\n"
                    (:err result))
               (assoc verification :aguafria/phase :zig-conversion-verify))))
     (assoc verification
            :clojure-source (:clojure-source rendered)
            :zig-source zig-source))))

(defn zig->clojure
  "Return a well-formatted Aguafria namespace for `path`."
  ([path] (zig->clojure path {}))
  ([path options] (:clojure-source (convert-file path options))))

(defn- record-conversion!
  [report]
  (swap! conversion-history
         (fn [history]
           (->> (conj history (assoc report :finished-at-ms
                                     (System/currentTimeMillis)))
                (take-last 200)
                vec)))
  report)

(defn convert-file!
  "Convert `input` to `output` and return a serializable report.

  Existing output is replaced only with `:overwrite? true`, unless its content
  is already identical."
  ([input output] (convert-file! input output {}))
  ([input output {:keys [overwrite?] :as options}]
   (let [{:keys [clojure-source report]} (convert-file input options)
         output-file (.getCanonicalFile (io/file output))
         existing (when (.isFile output-file) (slurp output-file))]
     (when (and existing (not= existing clojure-source) (not overwrite?))
       (throw (ex-info "Refusing to overwrite an existing converted namespace"
                       {:input (str input)
                        :output (.getAbsolutePath output-file)
                        :hint "Pass :overwrite? true to replace it."})))
     (io/make-parents output-file)
     (when-not (= existing clojure-source)
       (Files/writeString (.toPath output-file) clojure-source StandardCharsets/UTF_8
                          (into-array StandardOpenOption
                                      [StandardOpenOption/CREATE
                                       StandardOpenOption/TRUNCATE_EXISTING
                                       StandardOpenOption/WRITE])))
     (record-conversion!
      (assoc report
             :output-path (.getAbsolutePath output-file)
             :written? (not= existing clojure-source))))))

(defn- declared-namespace
  [^File file]
  (with-open [reader (PushbackReader. (io/reader file))]
    (loop []
      (let [form (read {:eof ::eof} reader)]
        (cond
          (= ::eof form)
          (throw (ex-info "Converted Clojure input has no namespace declaration"
                          {:path (.getAbsolutePath file)}))

          (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
          (second form)

          :else
          (recur))))))

(defn load-converted!
  "Load a generated Clojure file while externally collecting its declarations.

  The file itself remains completely ordinary—only `ns` and `az/...` forms—and
  all Vars are interned in that declared namespace. This optional loader avoids
  compiling intermediate module snapshots for a large converted Zig file.
  `:compile?` defaults to false; reevaluating any declaration normally after
  loading follows the standard hot-reload path."
  ([path] (load-converted! path {}))
  ([path {:keys [compile? replace?]
          :or {compile? false replace? true}}]
   (let [file (.getCanonicalFile (io/file path))
         declarations (atom [])]
     (when-not (.isFile file)
       (throw (ex-info "Converted Clojure input is not a regular file"
                       {:path (str path) :resolved (.getAbsolutePath file)})))
     (let [namespace (declared-namespace file)]
       (binding [runtime/*registration-batch* declarations]
         (load-file (.getAbsolutePath file)))
       (let [module (str namespace)
             modules (set (map :module @declarations))]
         (when-not (or (empty? modules) (= #{module} modules))
           (throw (ex-info "A converted file contains declarations for another namespace"
                           {:path (.getAbsolutePath file)
                            :namespace namespace
                            :modules modules
                            :declaration-count (count @declarations)})))
       (assoc (runtime/register-batch!
               @declarations
               {:module module :compile? compile? :replace? replace?})
              :path (.getAbsolutePath file)
              :namespace namespace))))))

(defn load-tree!
  "Bulk-load every generated `.clj` namespace below `root`, one module at a
  time, using `load-converted!`. Returns serializable per-file reports."
  ([root] (load-tree! root {}))
  ([root options]
   (let [root-file (.getCanonicalFile (io/file root))
         files (->> (file-seq root-file)
                    (filter #(and (.isFile ^File %)
                                  (str/ends-with? (.getName ^File %) ".clj")))
                    (sort-by #(.getAbsolutePath ^File %))
                    vec)
         reports (mapv (fn [^File file]
                         (try
                           (load-converted! file options)
                           (catch Throwable error
                             (throw (ex-info "Unable to load a converted namespace"
                                             {:path (.getAbsolutePath file)}
                                             error)))))
                       files)]
     {:root (.getAbsolutePath root-file)
      :file-count (count reports)
      :declaration-count (reduce + (map :declaration-count reports))
      :files reports})))

(defn- clojure-segment
  [segment]
  (-> segment
      (str/replace #"\.zig$" "")
      (str/replace "_" "-")
      (str/replace #"[^A-Za-z0-9.-]" "-")
      (str/replace #"-+" "-")
      (str/replace #"^[.-]+|[.-]+$" "")))

(defn- relative-namespace
  [prefix ^Path relative]
  (let [segments (mapv (comp clojure-segment str)
                       (iterator-seq (.iterator relative)))]
    (symbol (str/join "." (concat [(str prefix)] segments)))))

(defn- zig-files
  [root]
  (let [root-path (.toPath (.getCanonicalFile (io/file root)))]
    (with-open [paths (Files/walk root-path (make-array java.nio.file.FileVisitOption 0))]
      (->> (iterator-seq (.iterator paths))
           (filter #(Files/isRegularFile ^Path % (make-array java.nio.file.LinkOption 0)))
           (filter #(str/ends-with? (str %) ".zig"))
           (remove #(some #{".git" ".zig-cache" "zig-out"}
                          (map str (iterator-seq (.iterator (.relativize root-path ^Path %))))))
           (sort-by str)
           vec))))

(defn- parsed-imports
  [parsed]
  (into {}
        (keep
         (fn [node-index]
           (when-let [[_ _visibility _extern _lib _threadlocal _comptime
                       mut-token _type _align _addrspace _section init-node]
                      (get (:var-index parsed) node-index)]
             (when-let [import-name (and init-node
                                         (import-initializer parsed init-node))]
               [(token-text parsed (inc mut-token)) import-name])))
         (:root-decls parsed))))

(defn- conversion-output-file
  [^File output-root namespace-symbol]
  (io/file output-root
           (str (-> (str namespace-symbol)
                    (str/replace "." File/separator)
                    (str/replace "-" "_"))
                ".clj")))

(defn- module-name-index
  [plans]
  (->> plans
       (group-by #(str/replace (.getName ^File (:file %)) #"\.zig$" ""))
       (keep (fn [[module-name matches]]
               (when (= 1 (count matches)) [module-name (first matches)])))
       (into {})))

(defn- tree-import-bindings
  [plan plan-by-path module-index input-root-file]
  (into {}
        (keep
         (fn [[zig-alias import-name]]
           (let [relative-path (.getCanonicalPath
                                (io/file (.getParentFile ^File (:file plan))
                                         import-name))
                 root-module-path
                 (when-not (str/ends-with? import-name ".zig")
                   (.getCanonicalPath (io/file input-root-file
                                               (str import-name ".zig"))))
                 target (or (get plan-by-path relative-path)
                            (when root-module-path
                              (get plan-by-path root-module-path))
                            (get module-index import-name))]
             (when target
               (let [alias (get (:declaration-names plan) zig-alias)]
                 [zig-alias
                  {:alias alias
                   :namespace (:namespace target)
                   :import-name import-name
                   :declarations (:declaration-names target)}]))))
         (:imports plan))))

(defn convert-tree!
  "Convert every `.zig` file below `input-root` into `output-root`.

  `:namespace-prefix` is required. Returns per-file and aggregate statistics."
  [input-root output-root {:keys [namespace-prefix] :as options}]
  (when-not namespace-prefix
    (throw (ex-info "convert-tree! requires :namespace-prefix"
                    {:input-root (str input-root) :output-root (str output-root)})))
  (let [started (System/nanoTime)
        ;; A tree conversion uses one compiler/version probe and one helper
        ;; executable for every file. This matters for large Zig repositories,
        ;; while individual parse-file calls still notice toolchain changes.
        options (assoc options ::helper (ensure-helper! options))
        input-root-file (.getCanonicalFile (io/file input-root))
        input-path (.toPath input-root-file)
        output-file (.getCanonicalFile (io/file output-root))
        plans
        (mapv
         (fn [^Path input]
           (let [relative (.relativize input-path input)
                 namespace-symbol (relative-namespace namespace-prefix relative)
                 parsed (parse-file (str input) options)]
             {:path input
              :file (.getCanonicalFile (io/file (str input)))
              :relative relative
              :namespace namespace-symbol
              :parsed parsed
              :imports (parsed-imports parsed)
              :declaration-names (declaration-name-map parsed)}))
         (zig-files input-root))
        plan-by-path (into {} (map (juxt #(-> ^File (:file %) .getCanonicalPath)
                                         identity)
                                   plans))
        module-index (module-name-index plans)
        reports
        (mapv
         (fn [{:keys [^Path path relative namespace parsed] :as plan}]
           (let [output (conversion-output-file output-file namespace)]
             (convert-file! (str path) output
                            (assoc options
                                   ::parsed parsed
                                   :namespace namespace
                                   :import-bindings
                                   (tree-import-bindings plan plan-by-path
                                                         module-index input-root-file)
                                   :source-display-path
                                   (str (.normalize (.toPath
                                                    (io/file (str input-root)
                                                             (str relative)))))))))
         plans)
        fallbacks (reduce + (map :fallback-count reports))
        declarations (reduce + (map :declaration-count reports))
        report {:input-root (.getAbsolutePath (.getCanonicalFile (io/file input-root)))
                :output-root (.getAbsolutePath output-file)
                :namespace-prefix (symbol (str namespace-prefix))
                :file-count (count reports)
                :declaration-count declarations
                :structural-declaration-count
                (reduce + (map :structural-declaration-count reports))
                :raw-declaration-count (reduce + (map :raw-declaration-count reports))
                :fallback-count fallbacks
                :elapsed-ms (/ (- (System/nanoTime) started) 1e6)
                :files reports}]
    (record-conversion! report)))

(defn stats
  "Return aggregate and bounded per-operation converter statistics."
  []
  (let [history @conversion-history]
    {:summary {:operation-count (count history)
               :file-count (reduce + (map #(or (:file-count %) 1) history))
               :declaration-count (reduce + (map #(or (:declaration-count %) 0) history))
               :fallback-count (reduce + (map #(or (:fallback-count %) 0) history))}
     :history history}))
