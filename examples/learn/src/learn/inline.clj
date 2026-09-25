(ns learn.inline
  "Context-aware equivalents for the reference's short, non-file snippets.

  These are syntax references, not invented standalone execution tests. Keep
  expressions requiring their surrounding example pending until translated."
  (:require [aguafria.keyword :as k]
            [aguafria.std]
            [aguafria.std.builtin]
            [aguafria.std.crypto]
            [aguafria.std.debug]
            [aguafria.std.heap]
            [aguafria.std.math]
            [aguafria.std.testing]
            [aguafria.zig :as az]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.project :as project]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def rendered-differences
  ;; Both files are SHA-256 pinned by the reference snapshot. Do not rewrite the
  ;; published HTML to conceal a discrepancy with the tagged source template.
  {"snippet-446"
   {:template "std.debug.dumpErrorReturnTrace"
    :rendered "std.debug.dumpStackTrace"
    :reason "The published 0.16.0 page differs from the tagged template here."}})

(defn- rendered-snippet [snippet]
  (if-let [{:keys [template rendered] :as difference}
           (get rendered-differences (:id snippet))]
    (do
      (when-not (= template (:source snippet))
        (throw (ex-info "Reviewed upstream discrepancy no longer matches" snippet)))
      (assoc snippet :rendered-source rendered :upstream-difference difference))
    snippet))

(defn- normalized-signature [text]
  (some-> text (str/replace #"\s+" "") (str/replace ",)" ")")))

(defn- catalog-var [entry]
  (let [expected (or (:zig-name entry) (:zig-token entry) (:tag entry))
        var-name (if (:zig-name entry) (:name entry) (k/token-name expected))
        resolved (when var-name
                   (ns-resolve 'aguafria.keyword (symbol var-name)))]
    (when-not (and (var? resolved)
                   (= expected (:zig/name (meta resolved))))
      (throw (ex-info "The documented keyword must resolve to its actual Var"
                      {:entry entry})))
    (str "k/" var-name)))

(defn references
  "Build mappings from the installed compiler catalog, checking real Vars.
  Function signatures remain references: no fictitious argument bindings."
  []
  {:builtins (into {} (map (juxt :zig-name identity)) (k/entries))
   :tokens (into {}
                 (map (fn [entry]
                        [(or (:zig-token entry) (:tag entry)) entry]))
                 (concat (k/primitives) (k/language-keywords)))})

(defn equivalent
  "Return a checked reference mapping, or nil if context-aware work remains.
  An unchanged identifier refers to the surrounding example, not a JVM Var."
  [{:keys [builtins tokens]} source]
  (let [builtin-name (second (re-find #"^(@[A-Za-z][A-Za-z0-9]*)" source))
        builtin (get builtins builtin-name)
        token (get tokens source)]
    (cond
      (or (re-matches #"[iu][0-9]+" source)
          (and token
               (not (contains? #{"true" "false" "null" "undefined"} source))
               (some #(= source (:zig-token %)) (k/primitives))))
      (let [type-form (keyword source)]
        (when-not (= source (emitter/emit-type type-form))
          (throw (ex-info "Primitive type spelling changed" {:source source})))
        {:clojure-source (pr-str type-form)
         :verification :type-emission-matched
         :note "Type-position syntax. Use (az/type ...) when the type itself is a value."})

      (contains? #{"true" "false"} source)
      {:clojure-source source
       :verification :literal-emission-matched
       :note "Boolean literal; the spelling is unchanged."}

      token
      {:clojure-source (catalog-var token)
       :verification :catalog-var-matched
       :note (if (contains? #{"null" "undefined"} source)
               "Primitive value inside an Aguafria declaration. Require [aguafria.keyword :as k]."
               "Syntax reference, not a complete form. This real Var names the Zig keyword; surrounding forms supply its arguments. Require [aguafria.keyword :as k].")}

      (and builtin
           (or (= source builtin-name)
               (= (normalized-signature source)
                  (normalized-signature (:signature builtin)))))
      {:clojure-source (catalog-var builtin)
       :verification :catalog-var-matched
       :note "Built-in reference, not a call. Require [aguafria.keyword :as k]; invoke this Var inside an Aguafria declaration with the arguments described by the original signature."}

      (re-matches #"[A-Za-z_][A-Za-z0-9_]*" source)
      (let [form (symbol source)]
        (when-not (= source (emitter/emit-expr form))
          (throw (ex-info "Context identifier spelling changed" {:source source})))
        {:clojure-source source
         :verification :identifier-emission-matched
         :note "Name from the surrounding example or signature; unchanged here. This is not a newly defined or globally resolved JVM Var."})

      :else nil)))

(defn read-forms [source]
  (with-open [reader (java.io.PushbackReader. (java.io.StringReader. source))]
    (binding [*read-eval* false]
      (loop [forms []]
        (let [form (read {:eof ::eof} reader)]
          (if (= ::eof form)
            forms
            (recur (conj forms form))))))))

(def mapping-resources
  ["learn/inline/operators.edn"
   "learn/inline/types-and-pointers.edn"
   "learn/inline/contextual-values.edn"
   "learn/inline/library-references.edn"
   "learn/inline/builtins-and-context.edn"])

(defn authored-mappings []
  (reduce (fn [all resource]
            (reduce-kv (fn [all source mapping]
                         (when (contains? all source)
                           (throw (ex-info "Duplicate authored inline mapping"
                                           {:source source :resource resource})))
                         (assoc all source mapping))
                       all (edn/read-string (slurp (io/resource resource)))))
          {} mapping-resources))

(defn- emit-declarations
  [forms]
  (when-not (every? #(and (seq? %)
                          (contains? #{'az/defconst 'az/defcomptime} (first %)))
                    forms)
    (throw (ex-info "Inline declarations must be az/defconst or az/defcomptime forms"
                    {:forms forms})))
  (let [printed-forms (binding [*print-meta* true
                               *print-length* nil
                               *print-level* nil]
                       (pr-str forms))
        digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String printed-forms
                                   java.nio.charset.StandardCharsets/UTF_8))
        namespace-symbol (symbol (str "learn.inline.declarations-"
                                      (format "%064x" (java.math.BigInteger. 1 digest))))
        declarations (atom [])]
    (when (or (find-ns namespace-symbol)
              (contains? (loaded-libs) namespace-symbol))
      (throw (ex-info "Refusing to replace an existing inline namespace"
                      {:namespace namespace-symbol})))
    (try
      (binding [*ns* *ns*
                *read-eval* false
                runtime/*registration-batch* declarations
                project/*catalog-namespace* namespace-symbol]
        (eval (list 'ns namespace-symbol
                    '(:require [aguafria.keyword :as k]
                               [aguafria.zig :as az])))
        (doseq [form forms]
          (eval form)))
      (binding [*ns* (the-ns namespace-symbol)
                project/*catalog-namespace* namespace-symbol]
        (emitter/emit-module
         (str namespace-symbol)
         (map-indexed (fn [index declaration]
                        (cond-> declaration
                          (nil? (:source-order declaration))
                          (assoc :source-order index)))
                      @declarations)))
      (finally
        (remove-ns namespace-symbol)
        (dosync
          (alter @#'clojure.core/*loaded-libs* disj namespace-symbol))))))

(defn emit-mapping
  "Read the displayed forms and pass them through the normal Aguafria emitter.
  Alternative operator spellings are separate forms, not a sequential program."
  [{:keys [clojure-source kind alternatives?] :as mapping}]
  (if (= :syntax-note kind)
    (do
      (when-not (and (str/blank? clojure-source) (not (str/blank? (:note mapping))))
        (throw (ex-info "Syntax explanation must contain prose, not pretend code" mapping)))
      (-> mapping
          (dissoc :kind)
          (assoc :form-kind kind :verification :reviewed-syntax-note)))
    (let [forms (read-forms clojure-source)]
      (when (or (empty? forms)
                (and (not (contains? #{:statements :declarations} kind))
                     (not (and (= :reference kind) alternatives?))
                     (not= 1 (count forms))))
        (throw (ex-info "Unexpected number of inline forms" mapping)))
      (binding [*ns* (the-ns 'learn.inline)]
        (let [emitted
              (case kind
                :reference
                (do
                  (when-not (every? #(and (symbol? %) (var? (resolve %))) forms)
                    (throw (ex-info "Inline reference must name an existing Var" mapping)))
                  nil)
                :expr (az/emit-expr (first forms))
                :type (az/emit-type (first forms))
                :declarations (emit-declarations forms)
                :statements (str/join "\n" (map az/emit-stmt forms)))]
          (cond-> (-> mapping
                      (dissoc :kind)
                      (assoc :form-kind kind
                             :verification :emission-checked))
            emitted (assoc :zig-source emitted)))))))

(defn expression-body
  "An upstream operator example may introduce locals before its final value.
  Keep those declarations verbatim and yield the final expression in a block."
  [source]
  (if (re-find #"^(?:const|var) " source)
    (let [boundary (str/last-index-of source "\n")]
      (when-not boundary
        (throw (ex-info "A statement fragment needs a final expression" {:source source})))
      (str "blk: {\n" (subs source 0 boundary)
           "\nbreak :blk " (subs source (inc boundary)) ";\n}"))
    source))

(defn syntax-unit [mappings]
  (str/join
   "\n"
   (for [[index {:keys [form-kind zig-source]}] (map-indexed vector mappings)
         :when zig-source]
     (if (= :declarations form-kind)
       (str "const fragment_" index " = struct {\n" zig-source "\n};")
       (str "fn fragment_" index "() void {\n"
            (if (contains? #{:expr :type} form-kind) (str "_ = " zig-source ";") zig-source)
            "\n}")))))

(defn execution-unit [snippets language]
  (str/join
   "\n"
   (for [{:keys [id source zig-source]} snippets]
     (str "test \"" id "\" {\n"
          "const result = " (if (= :zig language) (expression-body source) zig-source) ";\n"
          "@import(\"std\").debug.print(\"{any}\\n\", .{result});\n}"))))

(defn translate
  "Each occurrence keeps its pinned source ID, including repeated snippets."
  [snippets]
  (let [snippets (mapv rendered-snippet snippets)
        catalog (references)
        authored (authored-mappings)
        source-of #(or (:rendered-source %) (:source %))
        by-source (into {}
                        (for [source (distinct (map source-of snippets))]
                          [source (if-let [mapping (get authored source)]
                                    (assoc (emit-mapping mapping) :status :translated)
                                    (when-let [mapping (equivalent catalog (str/trim source))]
                                      (assoc mapping :status :reference-mapped)))]))]
    (mapv (fn [snippet]
            (if-let [mapping (get by-source (source-of snippet))]
              (merge snippet mapping)
              snippet))
          snippets)))

(defn markup [original {:keys [id source clojure-source note form-kind]} escape-html]
  (str "<span class=\"learn-inline\" id=\"learn-" id "\""
       " aria-describedby=\"learn-" id "-note\""
       " aria-pressed=\"false\" aria-label=\"Show Aguafria equivalent for "
       (escape-html source) "\" title=\"" (escape-html note) "\">"
       "<span data-language=\"zig\">" original "</span>"
       "<span data-language=\"aguafria\" hidden>"
       (if (= :syntax-note form-kind)
         (escape-html note)
         (str "<code>" (escape-html clojure-source) "</code>"))
       "</span>"
       "<span class=\"learn-inline-note\" id=\"learn-" id "-note\" hidden>" (escape-html note)
       "</span></span>"))

(defn annotate
  "Pair non-figure code nodes in source order. Never modify original code HTML.
  Only consume a snippet when its decoded source matches; unmatched nodes are
  reported, not guessed. Entire figures are skipped to preserve source/output."
  [html snippets decode-html escape-html]
  (let [remaining (atom (vec snippets))
        matched (atom [])
        result
        (str/replace
         html #"(?s)<figure>.*?</figure>|<code>.*?</code>"
         (fn [original]
           (if (str/starts-with? original "<figure>")
             original
             (let [text (-> original
                            (str/replace #"<[^>]*>" "")
                            decode-html)
                   snippet (first @remaining)]
               (if (and snippet
                        (= (str/trim text)
                           (str/trim (or (:rendered-source snippet) (:source snippet)))))
                 (do
                   (swap! remaining subvec 1)
                   (swap! matched conj (:id snippet))
                   (if (contains? #{:reference-mapped :translated} (:status snippet))
                     (markup original snippet escape-html)
                     original))
                 original)))))]
    {:html result :matched-ids @matched :unmatched @remaining}))
