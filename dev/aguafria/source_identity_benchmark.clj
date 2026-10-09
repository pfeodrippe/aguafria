(ns aguafria.source-identity-benchmark
  "Compare identical persisted source keys, including native transfer overhead."
  (:require [aguafria.native-collector-benchmark :as corpus]
            [aguafria.zig.artifact :as artifact]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.toolchain :as toolchain]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
            MemoryLayout MemorySegment SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util ArrayList HexFormat]
           [java.util.regex Matcher]))

(set! *warn-on-reflection* true)

(def ^:private escaped-character #"[\\\"\n\t\r\x08\x0c]")

(defn jvm-key [^String source]
  (let [text (StringBuilder. (+ 64 (.length source)))
        matcher (re-matcher escaped-character source)]
    (.append text "[1 2 :bundle-source [:scalar \"")
    (loop [start 0]
      (if (.find ^Matcher matcher)
        (let [position (.start matcher)
              escaped (case (.charAt source position)
                        \newline "\\n" \tab "\\t" \return "\\r"
                        \backspace "\\b" \formfeed "\\f"
                        \" "\\\"" \\ "\\\\")]
          (.append text source (int start) (int position))
          (.append text ^String escaped)
          (recur (inc position)))
        (.append text source (int start) (.length source))))
    (.append text "\"]]")
    (.formatHex (HexFormat/of)
                (.digest (MessageDigest/getInstance "SHA-256")
                         (.getBytes (.toString text) StandardCharsets/UTF_8)))))

(defn jvm-loop-key [^String source]
  (let [text (StringBuilder. (+ 64 (.length source)))
        length (.length source)]
    (.append text "[1 2 :bundle-source [:scalar \"")
    (loop [index (long 0) start (long 0)]
      (if (< index length)
        (let [escaped (case (int (.charAt source (int index)))
                        10 "\\n" 9 "\\t" 13 "\\r" 8 "\\b" 12 "\\f"
                        34 "\\\"" 92 "\\\\" nil)]
          (if escaped
            (do (.append text source (int start) (int index))
                (.append text ^String escaped)
                (recur (unchecked-inc index) (unchecked-inc index)))
            (recur (unchecked-inc index) start)))
        (.append text source (int start) length)))
    (.append text "\"]]")
    (.formatHex (HexFormat/of)
                (.digest (MessageDigest/getInstance "SHA-256")
                         (.getBytes (.toString text) StandardCharsets/UTF_8)))))

(defn build! [directory]
  (let [batch (runtime/registration-batch)
        _ (binding [runtime/*registration-batch* batch]
            (require 'aguafria.native-source-identity :reload))
        source (emitter/emit-module "aguafria.native-source-identity"
                                    (runtime/collected-declarations batch))
        file (io/file directory "identity.zig")
        library (io/file directory (System/mapLibraryName "identity"))
        command [(toolchain/executable) "build-lib" "-dynamic" "-Ofast"
                 (str "-femit-bin=" library) (str "-Mroot=" file)]]
    (.mkdirs (io/file directory))
    (spit file source)
    (let [result (apply shell/sh command)]
      (when-not (zero? (:exit result))
        (throw (ex-info "Native identity benchmark build failed" (assoc result :command command)))))
    library))

(defn- invoke [^MethodHandle handle arguments]
  (.invokeWithArguments handle (ArrayList. ^java.util.Collection arguments)))

(defn- workspace [^Arena arena]
  (let [size 33554432
        storage (.allocate arena (long (+ size (* 1024 40) 16)) 8)]
    {:storage storage
     :buffer (.asSlice storage 0 size)
     :offsets (.asSlice storage size 4096)
     :lengths (.asSlice storage (+ size 4096) 4096)
     :output (.asSlice storage (+ size 8192) 32768)
     :guard (.asSlice storage (+ size 40960) 16)}))

(defn- pack! [workspace sources]
  (when (> (count sources) 1024) (throw (ex-info "Too many benchmark inputs" {})))
  (loop [index 0 offset 0]
    (if (< index (count sources))
      (let [bytes (.getBytes ^String (nth sources index) StandardCharsets/UTF_8)
            length (alength bytes)]
        (when (> (+ offset length) 33554432)
          (throw (ex-info "Benchmark inputs exceed workspace" {})))
        (MemorySegment/copy (MemorySegment/ofArray bytes) 0 (:buffer workspace) offset length)
        (.setAtIndex ^MemorySegment (:offsets workspace) ValueLayout/JAVA_INT index (int offset))
        (.setAtIndex ^MemorySegment (:lengths workspace) ValueLayout/JAVA_INT index (int length))
        (recur (inc index) (+ offset length)))
      offset)))

(defn- decode [workspace count]
  (mapv (fn [index]
          (.formatHex (HexFormat/of)
                      (.toArray (.asSlice ^MemorySegment (:output workspace) (long (* index 32)) 32)
                                ValueLayout/JAVA_BYTE)))
        (range count)))

(defn- measure [action]
  (dotimes [_ 5] (action))
  (let [thread (Thread/currentThread)
        bean ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
        bytes-before (.getThreadAllocatedBytes bean (.threadId thread))
        samples (mapv (fn [_] (let [start (System/nanoTime)]
                                (action) (/ (- (System/nanoTime) start) 1e6))) (range 15))]
    {:samples-ms samples :median-ms (nth (vec (sort samples)) 7)
     :allocated-bytes-per-run (/ (- (.getThreadAllocatedBytes bean (.threadId thread))
                                    bytes-before) 15)}))

(defn benchmark! [producer library output]
  (with-open [arena (Arena/ofConfined)]
    (let [lookup (SymbolLookup/libraryLookup (.toPath ^java.io.File library) arena)
          descriptor (FunctionDescriptor/of ValueLayout/JAVA_INT
                                            (into-array MemoryLayout
                                                        [ValueLayout/ADDRESS ValueLayout/JAVA_INT
                                                         ValueLayout/ADDRESS ValueLayout/ADDRESS
                                                         ValueLayout/JAVA_INT ValueLayout/ADDRESS]))
          handle (.downcallHandle (Linker/nativeLinker)
                                  (.orElseThrow (.find lookup "hash_batch")) descriptor
                                  (into-array Linker$Option []))
          workspace (workspace arena)
          _ (.fill ^MemorySegment (:guard workspace) (byte 90))
          sources (#'corpus/sources producer)
          native (fn [texts]
                   (let [total (pack! workspace texts)
                         status (invoke handle [(:buffer workspace) (int total)
                                                (:offsets workspace) (:lengths workspace)
                                                (int (count texts)) (:output workspace)])]
                     (assert (zero? status))
                     (decode workspace (count texts))))
          controls ["" "\"\\\n\t\r\b\f" "☔ é λ 😀"
                    (str (apply str (repeat 4095 "x")) "\n☔")
                    (String. (char-array (map char [0 1 127 55296 56320 55296])))]
          expected (mapv #(artifact/key-for :bundle-source %) (into sources controls))
          _ (assert (= expected (mapv jvm-key (into sources controls))))
          _ (assert (= expected (mapv jvm-loop-key (into sources controls))))
          _ (assert (= expected (native (into sources controls))))
          _ (assert (= 1 (invoke handle [(:buffer workspace) (int 0) (:offsets workspace)
                                         (:lengths workspace) (int 1025) (:output workspace)])))
          _ (assert (= 1 (invoke handle [(:buffer workspace) (int 33554433) (:offsets workspace)
                                         (:lengths workspace) (int 0) (:output workspace)])))
          _ (.setAtIndex ^MemorySegment (:lengths workspace) ValueLayout/JAVA_INT 0 (int 1))
          _ (assert (= 2 (invoke handle [(:buffer workspace) (int 0) (:offsets workspace)
                                         (:lengths workspace) (int 1) (:output workspace)])))
          result {:sources (count sources) :all-identities-equal? true
                  :positive-controls (count controls) :negative-controls 3
                  :bytes (reduce + (map #(alength (.getBytes ^String % StandardCharsets/UTF_8)) sources))
                  :current-production (measure #(mapv (partial artifact/key-for :bundle-source) sources))
                  :low-allocation-jvm (measure #(mapv jvm-key sources))
                  :primitive-loop-jvm (measure #(mapv jvm-loop-key sources))
                  :native-with-copy-and-decode (measure #(native sources))
                  :native-single-source-calls (measure #(mapv (fn [source] (first (native [source]))) sources))
                  :native-sixteen-source-batches
                  (measure #(into [] (mapcat native) (partition-all 16 sources)))
                  :native-heap-allocations 0 :native-workspace-allocations 1
                  :compile-mode "fast" :production-integrated? false}]
      (assert (every? #(= 90 (.getAtIndex ^MemorySegment (:guard workspace)
                                          ValueLayout/JAVA_BYTE (long %))) (range 16)))
      (spit output (artifact/print-data result))
      (prn result)
      result)))

(defn -main [producer output]
  (when-not (and producer output)
    (throw (ex-info "Supply a producer directory and output path" {})))
  (let [directory (.getParentFile (.getAbsoluteFile (io/file output)))
        _ (runtime/configure! {:cache-dir (str (io/file directory "support-cache"))})
        started (System/nanoTime)
        library (build! (str (io/file directory "native-identity-fresh")))
        build-ms (/ (- (System/nanoTime) started) 1e6)]
    (try
      (let [result (assoc (benchmark! producer library output) :helper-build-ms build-ms)]
        (spit output (artifact/print-data result))
        (prn {:helper-build-ms build-ms}))
      (finally (shutdown-agents)))))
