(ns aguafria.zig.source-scanner
  "Compiler-tokenized source facts for bundling. One bounded native workspace."
  (:require [aguafria.zig.artifact :as artifact]
            [aguafria.zig.compiler-work :as compiler-work]
            [aguafria.zig.toolchain :as toolchain]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell])
  (:import [java.io ByteArrayOutputStream File]
           [java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
            MemoryLayout MemoryLayout$PathElement MemorySegment SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle]
           [java.nio.channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption StandardOpenOption]
           [java.util ArrayList UUID]))

(def ^:private max-files 16)
(def ^:private max-bytes 33554432)
(def ^:private max-exports 64)
(def ^:private max-imports 1024)
(def ^:private max-identifiers 16384)

(defn- struct-layout [fields]
  (MemoryLayout/structLayout
   (into-array MemoryLayout
               (map (fn [[name layout]] (.withName ^MemoryLayout layout name)) fields))))

(def ^:private span-layout
  (struct-layout [["start" ValueLayout/JAVA_INT] ["end" ValueLayout/JAVA_INT]]))
(def ^:private import-layout
  (struct-layout [["start" ValueLayout/JAVA_INT] ["end" ValueLayout/JAVA_INT]
                  ["kind" ValueLayout/JAVA_INT]]))
(def ^:private facts-layout
  (struct-layout [["export_count" ValueLayout/JAVA_INT]
                  ["external_exports" ValueLayout/JAVA_INT]
                  ["dynamic_exports" ValueLayout/JAVA_INT]
                  ["external_syntax" ValueLayout/JAVA_INT]
                  ["import_count" ValueLayout/JAVA_INT]
                  ["identifier_count" ValueLayout/JAVA_INT]
                  ["exports" (MemoryLayout/sequenceLayout max-exports span-layout)]
                  ["imports" (MemoryLayout/sequenceLayout max-imports import-layout)]
                  ["identifiers" (MemoryLayout/sequenceLayout max-identifiers span-layout)]]))

(defn- field-offset ^long [^String name]
  (.byteOffset ^MemoryLayout facts-layout
               (into-array MemoryLayout$PathElement
                           [(MemoryLayout$PathElement/groupElement name)])))

(defn- program-source []
  (let [batch ((requiring-resolve 'aguafria.zig.runtime/registration-batch))
        collector (requiring-resolve 'aguafria.zig.runtime/*registration-batch*)]
    (with-bindings {collector batch}
      (require 'aguafria.native-tools.source-scanner :reload))
    ((requiring-resolve 'aguafria.zig.emitter/emit-module)
     "aguafria.native-tools.source-scanner"
     ((requiring-resolve 'aguafria.zig.runtime/collected-declarations) batch))))

(def ^:private program (delay (program-source)))
(def ^:private kernel
  (delay
    (let [source @program]
      {:source source
       :compiler (toolchain/executable)
       :key (artifact/key-for
             :source-scanner
             [source (dissoc (toolchain/information) :executable :materialized?) "fast"])})))
(defonce ^:private images (atom {}))
(defonce ^:private image-lock (Object.))

(defn- build-library! [cache key source compiler]
  (let [directory (.getAbsoluteFile (io/file cache "tools" "source-scanner" key))
        library (io/file directory (System/mapLibraryName "aguafria_source_scanner"))
        manifest-file (io/file directory "manifest.edn")]
    (.mkdirs directory)
    (with-open [channel (FileChannel/open (.toPath (io/file directory ".lock"))
                                          (into-array java.nio.file.OpenOption
                                                      [StandardOpenOption/CREATE
                                                       StandardOpenOption/WRITE]))
                file-lock (.lock channel)]
      (if (.isFile manifest-file)
        (let [manifest (edn/read-string (slurp manifest-file))]
          (when-not (and (= key (:key manifest)) (.isFile library)
                         (= (:library-bytes manifest) (.length library)))
            (throw (ex-info "Immutable source scanner library is damaged"
                            {:path (str directory) :key key}))))
        (let [source-file (io/file directory "scanner.zig")
              temporary (io/file directory (str "." (UUID/randomUUID) "-" (.getName library)))
              command [compiler "build-lib" "-dynamic" "-Ofast"
                       (str "-femit-bin=" temporary) (str "-Mroot=" source-file)]]
          (spit source-file source)
          (try
            (let [result (binding [compiler-work/*phase* :source-scanner]
                           (compiler-work/run-command! command #(apply shell/sh command)))]
              (when-not (and (zero? (:exit result)) (.isFile temporary) (pos? (.length temporary)))
                (throw (ex-info "Native source scanner compilation failed"
                                (assoc result :command command))))
              (Files/move (.toPath temporary) (.toPath library)
                          (into-array java.nio.file.CopyOption [StandardCopyOption/ATOMIC_MOVE]))
              (let [manifest (io/file directory (str "." (UUID/randomUUID) ".edn"))]
                (try
                  (spit manifest (artifact/print-data {:key key :library-bytes (.length library)}))
                  (Files/move (.toPath manifest) (.toPath manifest-file)
                              (into-array java.nio.file.CopyOption
                                          [StandardCopyOption/ATOMIC_MOVE]))
                  (finally (Files/deleteIfExists (.toPath manifest))))))
            (finally (Files/deleteIfExists (.toPath temporary)))))))
    library))

(defn- invoke [^MethodHandle handle arguments]
  (.invokeWithArguments handle (ArrayList. ^java.util.Collection arguments)))

(defn- open-image [^File library]
  (let [arena (Arena/ofShared)]
    (try
      (let [lookup (SymbolLookup/libraryLookup (.toPath library) arena)
            linker (Linker/nativeLinker)
            descriptor (FunctionDescriptor/of
                        ValueLayout/JAVA_INT
                        (into-array MemoryLayout [ValueLayout/ADDRESS ValueLayout/JAVA_INT
                                                  ValueLayout/ADDRESS ValueLayout/ADDRESS
                                                  ValueLayout/JAVA_INT ValueLayout/ADDRESS]))
            collect (.downcallHandle linker (.orElseThrow (.find lookup "collect_batch"))
                                     descriptor (into-array Linker$Option []))
            size (.downcallHandle linker (.orElseThrow (.find lookup "facts_size"))
                                  (FunctionDescriptor/of ValueLayout/JAVA_INT
                                                         (into-array MemoryLayout []))
                                  (into-array Linker$Option []))
            facts-bytes (.byteSize ^MemoryLayout facts-layout)
            _ (when-not (= facts-bytes (invoke size []))
                (throw (ex-info "Native source scanner ABI mismatch" {})))
            ;; One allocation, reused under the image lock; results never escape as pointers.
            storage (.allocate arena (long (+ max-bytes (* max-files 8)
                                              (* max-files facts-bytes) 16)) 8)
            output-offset (long (+ max-bytes (* max-files 8)))
            guard-offset (long (+ output-offset (* max-files facts-bytes)))]
        (.fill storage (byte 0))
        (.fill (.asSlice storage guard-offset 16) (byte 90))
        {:arena arena
         :collect collect
         :storage storage
         :buffer (.asSlice storage 0 (long max-bytes))
         :offsets (.asSlice storage (long max-bytes) (long (* max-files 4)))
         :lengths (.asSlice storage (long (+ max-bytes (* max-files 4))) (long (* max-files 4)))
         :output (.asSlice storage output-offset (long (* max-files facts-bytes)))
         :guard (.asSlice storage guard-offset 16)})
      (catch Throwable error (.close arena) (throw error)))))

(defn- image []
  (let [{:keys [source compiler key]} @kernel]
    (or (get @images key)
        (locking image-lock
          (or (get @images key)
              (let [cache (:cache-dir ((requiring-resolve 'aguafria.zig.runtime/configuration)))
                    image (open-image (build-library! cache key source compiler))]
                (swap! images assoc key image)
                image))))))

(defn- spans [^MemorySegment output base field count stride]
  (let [offset (+ base (field-offset field))]
    (mapv (fn [index]
            (let [start (+ offset (* index stride))]
              [(.get output ValueLayout/JAVA_INT (long start))
               (.get output ValueLayout/JAVA_INT (long (+ start 4)))])) (range count))))

(defn- decode [image ^bytes source index]
  (let [output ^MemorySegment (:output image)
        base (* index (.byteSize ^MemoryLayout facts-layout))
        field (fn [name] (.get output ValueLayout/JAVA_INT (long (+ base (field-offset name)))))
        export-count (field "export_count")
        import-count (field "import_count")
        identifier-count (field "identifier_count")
        _ (when-not (and (<= 0 export-count max-exports) (<= 0 import-count max-imports)
                         (<= 0 identifier-count max-identifiers))
            (throw (ex-info "Native scanner returned invalid record counts" {})))
        text (fn [[start end]]
               (when-not (<= 0 start end (alength source))
                 (throw (ex-info "Native scanner returned invalid source spans" {})))
               (String. source (int start) (int (- end start)) StandardCharsets/UTF_8))
        imports (spans output base "imports" import-count 12)]
    {:exports (mapv text (spans output base "exports" export-count 8))
     :external-exports? (= 1 (field "external_exports"))
     :dynamic-exports? (= 1 (field "dynamic_exports"))
     :external-declaration-syntax? (= 1 (field "external_syntax"))
     :imports (mapv (fn [index [start end]]
                      (let [kind (.get output ValueLayout/JAVA_INT
                                       (long (+ base (field-offset "imports") (* index 12) 8)))]
                        [(case kind 1 "import" 2 "embedFile"
                               (throw (ex-info "Invalid native import kind" {:kind kind})))
                         (when (pos? end) (text [start end]))])) (range) imports)
     :import-spans imports
     :identifier-spans (spans output base "identifiers" identifier-count 8)}))

(defn analyze-many!
  "Analyze at most sixteen sources using real Zig tokens. Explicitly rejects
  capacity violations; callers retain ordinary compiler diagnostics for code."
  [sources]
  (when (> (count sources) max-files)
    (throw (ex-info "Source scanner input exceeds capacity" {:reason :source-scanner-capacity})))
  (let [bytes (mapv #(.getBytes ^String % StandardCharsets/UTF_8) sources)
        total (reduce + 0 (map #(inc (alength ^bytes %)) bytes))]
    (when (> total max-bytes)
      (throw (ex-info "Source scanner input exceeds capacity" {:reason :source-scanner-capacity})))
    (let [image (image)]
      (locking image
        (loop [index 0 offset 0]
          (when (< index (count bytes))
            (let [source ^bytes (nth bytes index) length (alength source)]
              (MemorySegment/copy (MemorySegment/ofArray source) 0 (:buffer image) offset length)
              (.set ^MemorySegment (:buffer image) ValueLayout/JAVA_BYTE (+ offset length) (byte 0))
              (.setAtIndex ^MemorySegment (:offsets image) ValueLayout/JAVA_INT index (int offset))
              (.setAtIndex ^MemorySegment (:lengths image) ValueLayout/JAVA_INT index (int length))
              (recur (inc index) (+ offset length 1)))))
        (let [status (invoke (:collect image)
                             [(:buffer image) (int total) (:offsets image) (:lengths image)
                              (int (count bytes)) (:output image)])]
          (when-not (zero? status)
            (throw (ex-info "Zig source tokenizer rejected input"
                            {:reason :source-scanner-input :status status}))))
        (when-not (every? #(= 90 (.getAtIndex ^MemorySegment (:guard image)
                                              ValueLayout/JAVA_BYTE (long %)))
                          (range 16))
          (throw (ex-info "Native scanner violated its output bound" {})))
        (mapv #(decode image %1 %2) bytes (range))))))

(defn analyze! [source] (first (analyze-many! [source])))

(defn rewrite
  "Apply only token-confirmed UTF-8 spans. Source text, comments, strings and
  quoted identifiers remain verbatim except a requested root import argument."
  [source analysis renames root-module]
  (let [bytes (.getBytes ^String source StandardCharsets/UTF_8)
        text (fn [[start end]]
               (String. bytes (int start) (int (- end start)) StandardCharsets/UTF_8))
        identifiers (keep (fn [[start end :as span]]
                            (when-let [replacement (get renames (text span))]
                              [start end replacement])) (:identifier-spans analysis))
        imports (when root-module
                  (keep (fn [[[kind argument] [start end]]]
                          (when (= ["import" "\"root\""] [kind argument])
                            [start end (artifact/print-data root-module)]))
                        (map vector (:imports analysis) (:import-spans analysis))))
        replacements (mapv (fn [[start end replacement]]
                             [start end (.getBytes ^String replacement StandardCharsets/UTF_8)])
                           (sort-by first (concat identifiers imports)))
        capacity (+ (alength bytes)
                    (reduce + 0 (map (fn [[start end value]]
                                       (max 0 (- (alength ^bytes value) (- end start))))
                                     replacements)))
        output (ByteArrayOutputStream. capacity)]
    (loop [remaining (seq replacements) offset 0]
      (if-let [[start end value] (first remaining)]
        (do
          (when-not (<= offset start end (alength bytes))
            (throw (ex-info "Source rewrite spans overlap or exceed input" {})))
          (.write output bytes offset (- start offset))
          (.write output ^bytes value 0 (alength ^bytes value))
          (recur (next remaining) (long end)))
        (.write output bytes offset (- (alength bytes) offset))))
    (.toString output StandardCharsets/UTF_8)))
