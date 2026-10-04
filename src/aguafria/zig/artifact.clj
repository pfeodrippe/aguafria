(ns aguafria.zig.artifact
  "Deterministic identities for persisted native artifacts, not runtime values."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.util HexFormat]))

(def ^:private key-version 1)
(def ^:private native-abi-version 2)

(defn print-data
  "Print complete, readable machine data independently of REPL print settings."
  ([value] (print-data value true))
  ([value namespace-maps?]
   (binding [*print-length* nil *print-level* nil *print-meta* false
             *print-dup* false *print-readably* true
             *print-namespace-maps* namespace-maps?]
     (pr-str value))))

(defn- printed [value]
  (print-data value false))

(defn- canonical [value]
  ;; Tag collections so a map cannot collide with a vector of its entries.
  ;; Preserve ordered inputs, including argument order and emitted source text.
  (cond
    (record? value) [:record (.getName (class value)) (canonical (into {} value))]
    (map? value) [:map (->> value
                            (map (fn [[k v]] [(canonical k) (canonical v)]))
                            (sort-by (comp printed first)) vec)]
    (set? value) [:set (->> value (map canonical) (sort-by printed) vec)]
    (vector? value) [:vector (mapv canonical value)]
    (sequential? value) [:sequence (mapv canonical value)]
    (or (nil? value) (boolean? value) (string? value) (char? value)
        (number? value) (keyword? value) (symbol? value)
        (uuid? value) (inst? value)) [:scalar value]
    :else (throw (ex-info "Native artifact keys require stable data"
                          {:value-type (type value)}))))

(defn key-for
  "Full SHA-256 of versioned, canonical data. Domain separates artifact kinds.
  Bump native-abi-version when the JVM/native calling contract changes."
  [domain inputs]
  (let [data [key-version native-abi-version domain (canonical inputs)]
        digest (MessageDigest/getInstance "SHA-256")]
    (.formatHex (HexFormat/of) (.digest digest (.getBytes (printed data) "UTF-8")))))

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
