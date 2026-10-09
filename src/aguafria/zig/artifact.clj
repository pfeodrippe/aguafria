(ns aguafria.zig.artifact
  "Deterministic identities for persisted native artifacts, not runtime values."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io StringWriter Writer]
           [java.security MessageDigest]
           [java.util HexFormat LinkedHashMap]))

(def ^:private key-version 1)
(def ^:private native-abi-version 2)

(def ^:dynamic *source-key-cache* nil)

(defn source-key-cache
  "Preparation-local, content-keyed memoization with entry and byte bounds."
  ([] (source-key-cache 4096 33554432))
  ([entries bytes]
   (when-not (and (pos-int? entries) (pos-int? bytes))
     (throw (ex-info "Source-key cache limits must be positive integers" {})))
   {:entries (LinkedHashMap. 128 (float 0.75) true)
    :entry-limit entries :byte-limit bytes
    :statistics (volatile! {:hits 0 :misses 0 :evictions 0 :retained-bytes 0})}))

(defn source-key-statistics [cache]
  (locking cache
    (assoc @(:statistics cache) :retained-entries (.size ^LinkedHashMap (:entries cache)))))

(declare write-data)

(defn print-data
  "Print complete, readable machine data independently of REPL print settings."
  ([value] (print-data value true))
  ([value namespace-maps?]
   (binding [*print-length* nil *print-level* nil *print-meta* false
             *print-dup* false *print-readably* true
             *print-namespace-maps* namespace-maps?]
     (let [writer (StringWriter.)]
       (write-data value writer)
       (.toString writer)))))

(defn- write-string [^String value ^Writer writer]
  ;; Write unchanged runs together; Clojure's general printer writes each char.
  (.write writer "\"")
  (let [length (.length value)]
    (loop [index (long 0) start (long 0)]
      (if (< index length)
        (let [escaped (case (int (.charAt value (int index)))
                        10 "\\n" 9 "\\t" 13 "\\r" 8 "\\b" 12 "\\f"
                        34 "\\\"" 92 "\\\\" nil)]
          (if escaped
            (do (.write writer value (int start) (int (- index start)))
                (.write writer ^String escaped)
                (recur (unchecked-inc index) (unchecked-inc index)))
            (recur (unchecked-inc index) start)))
        (.write writer value (int start) (int (- length start))))))
  (.write writer "\""))

(def ^:private standard-printers
  (select-keys (methods print-method)
               [String clojure.lang.IPersistentVector clojure.lang.IPersistentMap
                clojure.lang.IPersistentSet clojure.lang.ISeq]))

(defn- standard-printer? [value interface]
  (= (get standard-printers interface) (get-method print-method (class value))))

(defn- write-sequence [values ^Writer writer ^String start ^String end]
  (.write writer start)
  (loop [values (seq values)]
    (when values
      (write-data (first values) writer)
      (when (next values) (.write writer " "))
      (recur (next values))))
  (.write writer end))

(defn- lifted-namespace [value]
  (when *print-namespace-maps*
    (let [k (ffirst value)
          prefix (when (qualified-ident? k) (namespace k))]
      (when (and prefix (every? #(and (qualified-ident? %)
                                      (= prefix (namespace %))) (keys value)))
        prefix))))

(defn- write-map [value ^Writer writer]
  (let [prefix (lifted-namespace value)]
    (when prefix (.write writer (str "#:" prefix)))
    (.write writer "{")
    (loop [entries (seq value)]
      (when entries
        (let [[k v] (first entries)
              k (if prefix
                  (if (symbol? k) (symbol (name k)) (keyword (name k)))
                  k)]
          (write-data k writer)
          (.write writer " ")
          (write-data v writer)
          (when (next entries) (.write writer ", "))
          (recur (next entries)))))
    (.write writer "}")))

(defn- write-data [value ^Writer writer]
  ;; Keep records, tagged metadata and custom print-methods authoritative.
  (cond
    (:type (meta value)) (print-method value writer)
    (and (string? value) (standard-printer? value String)) (write-string value writer)
    (and (vector? value) (standard-printer? value clojure.lang.IPersistentVector))
    (write-sequence value writer "[" "]")
    (and (map? value) (standard-printer? value clojure.lang.IPersistentMap))
    (write-map value writer)
    (and (set? value) (standard-printer? value clojure.lang.IPersistentSet))
    (write-sequence value writer "#{" "}")
    (and (seq? value) (standard-printer? value clojure.lang.ISeq))
    (write-sequence value writer "(" ")")
    :else (print-method value writer)))

(defn- write-key-data [value ^Writer writer]
  (cond
    (string? value) (write-string value writer)
    (vector? value)
    (do (.write writer "[")
        (loop [values (seq value)]
          (when values
            (write-key-data (first values) writer)
            (when (next values) (.write writer " "))
            (recur (next values))))
        (.write writer "]"))
    :else (print-method value writer)))

(declare write-canonical)

(defn- canonical-text [value]
  (let [writer (StringWriter.)]
    (write-canonical value writer)
    (.toString writer)))

(defn- write-canonical-sequence [values ^Writer writer]
  (loop [values (seq values)]
    (when values
      (write-canonical (first values) writer)
      (when (next values) (.write writer " "))
      (recur (next values)))))

(defn- write-canonical [value ^Writer writer]
  ;; Tag collections so a map cannot collide with a vector of its entries.
  ;; Preserve ordered inputs, including argument order and emitted source text.
  (cond
    (record? value)
    (do (.write writer "[:record ")
        (write-string (.getName (class value)) writer)
        (.write writer " ")
        (write-canonical (into {} value) writer)
        (.write writer "]"))
    (map? value)
    (do (.write writer "[:map [")
        (loop [entries (seq (sort-by #(canonical-text (key %)) value))]
          (when entries
            (let [[k v] (first entries)]
              (.write writer "[")
              (write-canonical k writer)
              (.write writer " ")
              (write-canonical v writer)
              (.write writer "]")
              (when (next entries) (.write writer " "))
              (recur (next entries)))))
        (.write writer "]]"))
    (or (set? value) (vector? value) (sequential? value))
    (do (.write writer (cond (set? value) "[:set [" (vector? value) "[:vector ["
                             :else "[:sequence ["))
        (write-canonical-sequence (if (set? value) (sort-by canonical-text value) value) writer)
        (.write writer "]]"))
    (or (nil? value) (boolean? value) (string? value) (char? value)
        (number? value) (keyword? value) (symbol? value)
        (uuid? value) (inst? value))
    (do (.write writer "[:scalar ")
        (write-key-data value writer)
        (.write writer "]"))
    :else (throw (ex-info "Native artifact keys require stable data"
                          {:value-type (type value)}))))

(defn- calculate-key
  "Full SHA-256 of versioned, canonical data. Domain separates artifact kinds.
  Bump native-abi-version when the JVM/native calling contract changes."
  [domain inputs]
  (binding [*print-length* nil *print-level* nil *print-meta* false
            *print-dup* false *print-readably* true *print-namespace-maps* false]
    (let [writer (StringWriter.)
          digest (MessageDigest/getInstance "SHA-256")]
      (.write writer (str "[" key-version " " native-abi-version " "))
      (write-key-data domain writer)
      (.write writer " ")
      (write-canonical inputs writer)
      (.write writer "]")
      (.formatHex (HexFormat/of) (.digest digest (.getBytes (.toString writer) "UTF-8"))))))

(defn- cached-source-key [cache domain ^String inputs]
  (locking cache
    (let [entries ^LinkedHashMap (:entries cache)
          statistics (:statistics cache)
          identity [key-version native-abi-version domain inputs]]
      (if-let [entry (.get entries identity)]
        (do (vswap! statistics update :hits inc) (:key entry))
        (let [key (calculate-key domain inputs)
              bytes (+ 128 (* 2 (.length inputs)))]
          (vswap! statistics update :misses inc)
          (when (<= bytes (:byte-limit cache))
            (loop []
              (when (or (>= (.size entries) (:entry-limit cache))
                        (> (+ bytes (:retained-bytes @statistics)) (:byte-limit cache)))
                (let [iterator (.iterator (.entrySet entries))
                      entry ^java.util.Map$Entry (.next iterator)]
                  (vswap! statistics #(-> % (update :evictions inc)
                                          (update :retained-bytes - (:bytes (.getValue entry)))))
                  (.remove iterator)
                  (recur))))
            (.put entries identity {:key key :bytes bytes})
            (vswap! statistics update :retained-bytes + bytes))
          key)))))

(defn key-for
  "Exact versioned SHA-256 identity. Preparation may memoize immutable source
  strings by their complete contents; paths, timestamps and hashCode are not keys."
  [domain inputs]
  (if (and *source-key-cache* (keyword? domain) (string? inputs))
    (cached-source-key *source-key-cache* domain inputs)
    (calculate-key domain inputs)))

(defn- file-digest [file]
  ;; Do not trust (size, mtime) as content identity: editors/build tools can
  ;; preserve both while replacing bytes. Stream instead of retaining file data.
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 65536)]
    (with-open [stream (io/input-stream file)]
      (loop []
        (let [n (.read stream buffer)]
          (when (pos? n)
            (.update digest buffer 0 n)
            (recur)))))
    (.formatHex (HexFormat/of) (.digest digest))))

(defn argument-identity
  "Fingerprint a compiler/linker argument. Relocatable object files are identified
  by bytes; preserve paths for sources, shared libraries and archives (including
  thin archives), whose location can affect compilation or linking."
  [argument]
  (let [file (io/file argument)]
    (if-not (.isFile file)
      [:argument argument]
      (let [object? (some #(str/ends-with? (.getName file) %) [".o" ".obj"])]
        [:file (when-not object? (.getCanonicalPath file)) (file-digest file)]))))

(defn compiler-options-identity
  "Replace file arguments with fingerprints in the key only, never the command.
  Module source paths remain significant for @src and relative assets."
  [options]
  (cond-> options
    (contains? options :zig-args)
    (update :zig-args #(mapv argument-identity %))
    (contains? options :module-zig-args)
    (update :module-zig-args
            #(into {} (map (fn [[module args]]
                             [module (mapv argument-identity args)])) %))))
