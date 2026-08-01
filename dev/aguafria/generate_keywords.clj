(ns aguafria.generate-keywords
  "Generate Aguafria's Zig token catalog from the installed Zig and ZLS.

  Zig's `std/zig/BuiltinFn.zig` is the completeness authority. ZLS enriches
  those entries with signatures and language-reference documentation."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [java.io BufferedInputStream BufferedOutputStream
            ByteArrayOutputStream File]
           [java.math BigInteger]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.security MessageDigest]
           [java.util.concurrent TimeUnit]
           [java.lang ProcessBuilder$Redirect]))

(def ^:private catalog-path
  "resources/aguafria/zig-builtins.edn")

(def ^:private reader-token-specs
  [{:name "bit-not"
    :zig-token "~"
    :zig-tag "tilde"
    :kind :operator
    :param-count 1
    :documentation "Bitwise NOT. This Var exists because `~` is reserved by the Clojure reader."}
   {:name "bit-xor"
    :zig-token "^"
    :zig-tag "caret"
    :kind :operator
    :param-count nil
    :minimum-param-count 2
    :documentation "Bitwise XOR. This Var exists because `^` is metadata syntax in the Clojure reader."}
   {:name "div-assign"
    :zig-token "/="
    :zig-tag "slash_equal"
    :kind :assignment
    :param-count 2
    :documentation "Division assignment. This Var exists because `/=` is not a valid Clojure token."}])

(defn- command-output!
  [command]
  (let [process (-> (ProcessBuilder. ^java.util.List (mapv str command))
                    (.redirectErrorStream true)
                    (.start))
        output (slurp (.getInputStream process))
        exit (.waitFor process)]
    (when-not (zero? exit)
      (throw (ex-info "External command failed while generating Zig keywords"
                      {:command command :exit exit :output output})))
    (str/trim output)))

(defn- sha256-file
  [file]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (Files/readAllBytes (.toPath (io/file file)))]
    (.update digest bytes)
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn- zig-environment
  [zig]
  (let [version (command-output! [zig "version"])
        env-output (command-output! [zig "env"])
        lib-dir (second (re-find #"(?m)^\s*\.lib_dir\s*=\s*\"([^\"]+)\"" env-output))]
    (when-not lib-dir
      (throw (ex-info "Could not find .lib_dir in `zig env` output"
                      {:zig zig :output env-output})))
    {:executable zig
     :version version
     :lib-dir lib-dir
     :builtin-source (io/file lib-dir "std/zig/BuiltinFn.zig")
     :tokenizer-source (io/file lib-dir "std/zig/tokenizer.zig")}))

(defn- source-segments
  [source]
  (let [lines (str/split-lines source)
        starts (->> lines
                    (keep-indexed
                     (fn [index line]
                       (when-let [[_ zig-name]
                                  (re-matches #"\s*\"(@[A-Za-z][A-Za-z0-9]*)\",\s*" line)]
                         [index zig-name])))
                    vec)]
    (mapv (fn [position [start zig-name]]
            (let [end (or (first (nth starts (inc position) nil)) (count lines))]
              [zig-name (str/join "\n" (subvec (vec lines) start end))]))
          (range (count starts))
          starts)))

(defn- parse-builtin-segment
  [[zig-name segment]]
  (let [param-text (second (re-find #"\.param_count\s*=\s*(null|[0-9]+)" segment))
        tag-match (re-find #"\.tag\s*=\s*\.(?:@\"([^\"]+)\"|([A-Za-z0-9_]+))" segment)
        eval-match (re-find #"\.eval_to_error\s*=\s*\.([A-Za-z0-9_]+)" segment)]
    (when-not (and param-text tag-match)
      (throw (ex-info "Could not parse a Zig BuiltinFn entry"
                      {:zig-name zig-name :segment segment})))
    (sorted-map
     :allows-lvalue? (boolean (re-find #"\.allows_lvalue\s*=\s*true" segment))
     :eval-to-error (keyword (or (second eval-match) "never"))
     :illegal-outside-function?
     (boolean (re-find #"\.illegal_outside_function\s*=\s*true" segment))
     :name (subs zig-name 1)
     :param-count (when-not (= "null" param-text) (parse-long param-text))
     :tag (or (second tag-match) (nth tag-match 2))
     :zig-name zig-name)))

(defn- parse-builtins
  [file]
  (let [entries (->> (slurp file)
                     source-segments
                     (mapv parse-builtin-segment))
        names (map :zig-name entries)]
    (when (or (< (count entries) 50)
              (not= (count entries) (count (distinct names))))
      (throw (ex-info "The Zig builtin parser produced an implausible catalog"
                      {:file (str file)
                       :count (count entries)
                       :unique-count (count (distinct names))})))
    (vec (sort-by :name entries))))

(defn- parse-keywords
  [file]
  (->> (re-seq #"\.\{\s*\"([A-Za-z][A-Za-z0-9]*)\"\s*,\s*\.keyword_([A-Za-z0-9_]+)\s*\},"
               (slurp file))
       (map (fn [[_ spelling tag]]
              (sorted-map :name spelling :tag tag)))
       distinct
       (sort-by :name)
       vec))

(defn- tokenizer-tags
  [file]
  (->> (re-seq #"(?m)^\s{8}([A-Za-z][A-Za-z0-9_]*),\s*$" (slurp file))
       (map second)
       set))

(defn- read-lsp-line!
  [^BufferedInputStream input]
  (let [output (ByteArrayOutputStream.)]
    (loop []
      (let [byte (.read input)]
        (cond
          (= -1 byte) (throw (ex-info "ZLS closed its output stream" {}))
          (= 10 byte) (-> (.toString output StandardCharsets/UTF_8)
                          (str/replace #"\r$" ""))
          :else (do (.write output byte) (recur)))))))

(defn- read-lsp-message!
  [^BufferedInputStream input]
  (let [headers (loop [headers {}]
                  (let [line (read-lsp-line! input)]
                    (if (str/blank? line)
                      headers
                      (let [[header value] (str/split line #":" 2)]
                        (recur (assoc headers (str/lower-case header)
                                      (str/trim value)))))))
        content-length (some-> (get headers "content-length") parse-long)]
    (when-not content-length
      (throw (ex-info "ZLS response omitted Content-Length" {:headers headers})))
    (let [payload (.readNBytes input (int content-length))]
      (when-not (= content-length (alength payload))
        (throw (ex-info "ZLS response ended before Content-Length bytes"
                        {:expected content-length :actual (alength payload)})))
      (json/read-str (String. payload StandardCharsets/UTF_8) :key-fn keyword))))

(defn- write-lsp-message!
  [^BufferedOutputStream output message]
  (let [payload (.getBytes (json/write-str message) StandardCharsets/UTF_8)
        header (.getBytes (str "Content-Length: " (alength payload) "\r\n\r\n")
                          StandardCharsets/UTF_8)]
    (.write output header)
    (.write output payload)
    (.flush output)))

(defn- await-response!
  [input id]
  (loop []
    (let [message (read-lsp-message! input)]
      (if (= id (:id message)) message (recur)))))

(defn- zls-completions
  [zls]
  (let [process (-> (ProcessBuilder. ^java.util.List [zls "--disable-lsp-logs"])
                    (.redirectError ProcessBuilder$Redirect/INHERIT)
                    (.start))
        input (BufferedInputStream. (.getInputStream process))
        output (BufferedOutputStream. (.getOutputStream process))
        source "const aguafria_catalog = @"
        uri (str (.toURI (io/file ".aguafria-zls-catalog.zig")))]
    (try
      (write-lsp-message!
       output
       {:jsonrpc "2.0" :id 1 :method "initialize"
        :params {:processId nil
                 :rootUri (str (.toURI (io/file ".")))
                 :capabilities
                 {:textDocument
                  {:completion
                   {:completionItem
                    {:documentationFormat ["markdown" "plaintext"]}}}}}})
      (let [initialize (await-response! input 1)]
        (when (:error initialize)
          (throw (ex-info "ZLS initialize failed" {:response initialize}))))
      (write-lsp-message! output {:jsonrpc "2.0" :method "initialized" :params {}})
      (write-lsp-message!
       output
       {:jsonrpc "2.0" :method "textDocument/didOpen"
        :params {:textDocument {:uri uri :languageId "zig" :version 1 :text source}}})
      (write-lsp-message!
       output
       {:jsonrpc "2.0" :id 2 :method "textDocument/completion"
        :params {:textDocument {:uri uri}
                 :position {:line 0 :character (count source)}
                 :context {:triggerKind 2 :triggerCharacter "@"}}})
      (let [response (await-response! input 2)
            result (:result response)
            items (if (map? result) (:items result) result)]
        (when (:error response)
          (throw (ex-info "ZLS builtin completion failed" {:response response})))
        (into {}
              (keep (fn [{:keys [label detail documentation]}]
                      (when (str/starts-with? label "@")
                        [label {:signature detail
                                :documentation
                                (if (map? documentation)
                                  (:value documentation)
                                  documentation)
                                :documentation-format
                                (keyword (or (when (map? documentation)
                                               (:kind documentation))
                                             "plaintext"))}]))
                    items)))
      (finally
        (try
          (write-lsp-message! output
                              {:jsonrpc "2.0" :id 3 :method "shutdown" :params nil})
          (catch Throwable _))
        (.destroy process)
        (.waitFor process 2 TimeUnit/SECONDS)
        (when (.isAlive process) (.destroyForcibly process))))))

(defn- optional-zls-data
  [zls zig-version]
  (try
    (let [zls-version (command-output! [zls "--version"])]
      (if (= zig-version zls-version)
        {:version zls-version :completions (zls-completions zls)}
        (do
          (binding [*out* *err*]
            (println "Skipping ZLS docs: Zig" zig-version "and ZLS" zls-version
                     "do not match."))
          nil)))
    (catch Throwable error
      (binding [*out* *err*]
        (println "Skipping optional ZLS docs:" (ex-message error)))
      nil)))

(defn- fallback-documentation
  [{:keys [zig-name param-count eval-to-error allows-lvalue?
           illegal-outside-function?]}]
  (str "Zig compiler builtin `" zig-name "`. The compiler table declares "
       (if (some? param-count)
         (str param-count " parameter" (when (not= 1 param-count) "s"))
         "a variable parameter count")
       ", error evaluation `" (name eval-to-error) "`"
       (when allows-lvalue? ", and permits use as an lvalue")
       (when illegal-outside-function? ", and restricts it to function scope")
       "."))

(defn generate-catalog
  "Return a fresh catalog derived from Zig and, when available, matching ZLS."
  [{:keys [zig zls] :or {zig (or (System/getenv "AGUAFRIA_ZIG") "zig")
                              zls (or (System/getenv "AGUAFRIA_ZLS") "zls")}}]
  (let [{:keys [version builtin-source tokenizer-source] :as zig-env}
        (zig-environment zig)
        _ (doseq [^File file [builtin-source tokenizer-source]]
            (when-not (.isFile file)
              (throw (ex-info "The installed Zig source file was not found"
                              {:file (str file) :zig zig-env}))))
        builtins (parse-builtins builtin-source)
        keywords (parse-keywords tokenizer-source)
        token-tags (tokenizer-tags tokenizer-source)
        reader-tokens
        (mapv (fn [{:keys [zig-tag] :as token}]
                (when-not (contains? token-tags zig-tag)
                  (throw (ex-info "Aguafria reader token disappeared from Zig's tokenizer"
                                  {:tag zig-tag :source (str tokenizer-source)})))
                (into (sorted-map) token))
              reader-token-specs)
        zls-data (optional-zls-data zls version)
        completions (:completions zls-data)
        builtins (mapv (fn [builtin]
                         (let [completion (get completions (:zig-name builtin))]
                           (into (sorted-map)
                                 (merge builtin
                                        {:documentation
                                         (or (:documentation completion)
                                             (fallback-documentation builtin))
                                         :documentation-format
                                         (or (:documentation-format completion) :markdown)
                                         :documentation-source
                                         (if completion :zls-langref :zig-compiler-table)
                                         :signature
                                         (or (:signature completion)
                                             (str (:zig-name builtin) "(...)"))}))))
                       builtins)
        compiler-names (set (map :zig-name builtins))
        zls-names (set (keys completions))]
    (when (and (seq completions) (not= compiler-names zls-names))
      (binding [*out* *err*]
        (println "ZLS enrichment differs from Zig's authoritative builtin table:"
                 {:missing-in-zls (sort (set/difference compiler-names zls-names))
                  :extra-in-zls (sort (set/difference zls-names compiler-names))})))
    (sorted-map
     :builtins builtins
     :generated-by "aguafria.generate-keywords"
     :keywords keywords
     :reader-tokens reader-tokens
     :schema-version 1
     :sources
     (sorted-map
      :builtin-table
      (sorted-map :path "std/zig/BuiltinFn.zig"
                  :sha256 (sha256-file builtin-source))
      :language-reference
      (when zls-data
        (sorted-map
         :extra-builtins (vec (sort (set/difference zls-names compiler-names)))
         :missing-builtins (vec (sort (set/difference compiler-names zls-names)))
         :provider :zls
         :version (:version zls-data)))
      :tokenizer
      (sorted-map :path "std/zig/tokenizer.zig"
                  :sha256 (sha256-file tokenizer-source)))
     :zig-version version)))

(defn- render-catalog
  [catalog]
  (binding [*print-length* nil
            *print-level* nil]
    (with-out-str (pprint/pprint catalog))))

(defn- compiler-derived-shape
  "Remove optional ZLS enrichment so `--check` also works without ZLS."
  [catalog]
  (-> catalog
      (update :builtins
              #(mapv (fn [builtin]
                       (dissoc builtin :documentation :documentation-format
                               :documentation-source :signature))
                     %))
      (update :sources dissoc :language-reference)))

(defn -main
  [& args]
  (let [check? (some #{"--check"} args)
        unknown (remove #{"--check"} args)]
    (when (seq unknown)
      (throw (ex-info "Unknown keyword generator options"
                      {:unknown unknown :supported ["--check"]})))
    (let [catalog (generate-catalog {})
          rendered (render-catalog catalog)
          output (io/file catalog-path)
          existing (when (.isFile output) (slurp output))
          existing-catalog (when (and check? existing) (edn/read-string existing))
          current? (when check?
                     (if (get-in catalog [:sources :language-reference])
                       (= existing rendered)
                       (= (compiler-derived-shape existing-catalog)
                          (compiler-derived-shape catalog))))]
      (if check?
        (if current?
          (println "Zig keyword catalog is current:" catalog-path)
          (do
            (binding [*out* *err*]
              (println "Zig keyword catalog is stale. Run: clojure -M:generate-keywords"))
            (System/exit 1)))
        (do
          (io/make-parents output)
          (spit output rendered)
          (println "Generated" catalog-path "with"
                   (count (:builtins catalog)) "Zig builtins."))))))
