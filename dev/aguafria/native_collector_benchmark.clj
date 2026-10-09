(ns aguafria.native-collector-benchmark
  "Compare the existing source lexer with a bounded ReleaseFast Zig batch."
  (:require [aguafria.zig.bundle :as bundle]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
            MemoryLayout MemorySegment SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle]
           [java.nio.charset StandardCharsets]
           [java.util ArrayList]))

(def facts-bytes 536)
(def max-files 1024)
(def max-bytes 33554432)

(defn- invoke [^MethodHandle handle arguments]
  (.invokeWithArguments handle (ArrayList. ^java.util.Collection arguments)))

(defn- sources [baseline]
  (let [events (with-open [reader (io/reader (io/file baseline "events.edn"))]
                 (mapv edn/read-string (line-seq reader)))
        paths (->> events
                   (filter #(and (= :command (:kind %)) (zero? (:exit %))
                                 (= "build-lib" (second (:command %)))
                                 (some #{"-fno-emit-bin"} (:command %))))
                   (mapcat :command)
                   (filter #(str/starts-with? % "-M"))
                   (map #(second (str/split % #"=" 2))) distinct)]
    (vec (distinct (map slurp paths)))))

(defn- pack! [^Arena arena texts]
  (let [started (System/nanoTime)
        bytes (mapv #(.getBytes ^String % StandardCharsets/UTF_8) texts)
        lengths (mapv alength bytes)
        offsets (vec (butlast (reductions + 0 (map inc lengths))))
        total (reduce + (map inc lengths))
        count (count texts)
        _ (assert (and (<= 1 count max-files) (<= total max-bytes)))
        padded (* 8 (quot (+ total 7) 8))
        output-size (* count facts-bytes)
        ;; One native allocation for source bytes, metadata, results and guards.
        storage (.allocate arena (+ padded (* count 8) output-size 16) 8)
        buffer (.asSlice storage 0 total)
        offset-buffer (.asSlice storage padded (* count 4))
        length-buffer (.asSlice storage (+ padded (* count 4)) (* count 4))
        output (.asSlice storage (+ padded (* count 8)) output-size)
        guard (.asSlice storage (+ padded (* count 8) output-size) 16)]
    (.fill storage (byte 0))
    (.fill guard (byte 90))
    (doseq [index (range count)]
      (MemorySegment/copy (MemorySegment/ofArray ^bytes (nth bytes index)) 0
                          buffer (long (nth offsets index)) (long (nth lengths index)))
      (.setAtIndex offset-buffer ValueLayout/JAVA_INT index (int (nth offsets index)))
      (.setAtIndex length-buffer ValueLayout/JAVA_INT index (int (nth lengths index))))
    {:buffer buffer :offsets offset-buffer :lengths length-buffer :output output
     :guard guard :total total :count count :texts texts :bytes bytes
     :packing-ms (/ (- (System/nanoTime) started) 1e6)}))

(defn- arguments [workspace rounds]
  [(:buffer workspace) (int (:total workspace)) (:offsets workspace)
   (:lengths workspace) (int (:count workspace)) (:output workspace) (int rounds)])

(defn- guard-intact? [{:keys [guard]}]
  (every? #(= 90 (.getAtIndex ^MemorySegment guard ValueLayout/JAVA_BYTE %)) (range 16)))

(defn- decode [workspace index]
  (let [base (* facts-bytes index)
        field (fn [offset] (.get ^MemorySegment (:output workspace)
                                 ValueLayout/JAVA_INT (+ base offset)))
        count (field 0)
        _ (assert (<= 0 count 64))
        source ^bytes (nth (:bytes workspace) index)]
    {:exports (mapv (fn [export]
                      (let [start (field (+ 24 (* export 4)))
                            end (field (+ 280 (* export 4)))]
                        (String. source start (- end start) StandardCharsets/UTF_8)))
                    (range count))
     :external-exports? (= 1 (field 4))
     :dynamic-exports? (= 1 (field 8))
     :external-declaration-syntax? (= 1 (field 12))
     :import-count (field 16) :invalid-count (field 20)}))

(defn- expected [text]
  (let [facts (#'bundle/analyze-source text)]
    (-> (select-keys facts [:exports :external-exports? :dynamic-exports?
                            :external-declaration-syntax?])
        (assoc :import-count (count (:imports facts)) :invalid-count 0))))

(defn- measure [f samples rounds]
  (let [values (mapv (fn [_]
                       (let [started (System/nanoTime)]
                         (f)
                         (/ (- (System/nanoTime) started) (* 1e6 rounds))))
                     (range samples))]
    {:samples samples :rounds-per-sample rounds
     :mean-ms (/ (reduce + values) samples) :min-ms (apply min values)
     :max-ms (apply max values) :values-ms values}))

(defn- controls! [collect library-arena]
  (let [positive (pack! library-arena
                        ["// export fn ignored() void {}\nexport fn __aguafria_ok() void {}"
                         "const s = \"extern export fn ignored @export @import\";"
                         "extern fn puts([*:0]const u8) c_int;\n"
                         "comptime { @export(&foo, .{ .name = \"foo\" }); }"
                         "const x = @import(\"std\");\n"])
        expected-facts
        (assoc (mapv expected (:texts positive)) 3
               {:exports [] :external-exports? false :dynamic-exports? true
                :external-declaration-syntax? false :import-count 0 :invalid-count 0})]
    ;; The old regex also counts the word in @export as a declaration. Zig's
    ;; builtin token correctly reports only dynamic export for this fixture.
    (assert (= 0 (invoke collect (arguments positive 1))))
    (doseq [index (range (:count positive))]
      (assert (= (nth expected-facts index) (decode positive index))
              (pr-str {:index index :expected (nth expected-facts index)
                       :actual (decode positive index)})))
    (assert (guard-intact? positive))
    (let [invalid (pack! library-arena [(str (char 1))])
          excessive (pack! library-arena
                           [(apply str (map #(str "export fn __aguafria_" % "() void {}\n")
                                            (range 65)))])
          bounds (-> (arguments positive 1) (assoc 4 (int 1025)))
          rounds (-> (arguments positive 1) (assoc 6 (int 0)))
          invalid-end (pack! library-arena ["x"])]
      (.setAtIndex ^MemorySegment (:lengths invalid-end) ValueLayout/JAVA_INT 0 (int 2))
      (assert (= 1 (invoke collect bounds)))
      (assert (= 1 (invoke collect rounds)))
      (assert (= 2 (invoke collect (arguments invalid-end 1))))
      (assert (= 3 (invoke collect (arguments excessive 1))))
      (assert (= 4 (invoke collect (arguments invalid 1))))
      (assert (every? guard-intact? [positive invalid excessive invalid-end])))
    {:positive-files 5 :negative-cases 5 :guards-intact? true}))

(defn benchmark! [baseline library output]
  (with-open [arena (Arena/ofConfined)]
    (let [lookup (SymbolLookup/libraryLookup (.toPath (io/file library)) arena)
          linker (Linker/nativeLinker)
          descriptor (FunctionDescriptor/of
                      ValueLayout/JAVA_INT
                      (into-array MemoryLayout
                                  [ValueLayout/ADDRESS ValueLayout/JAVA_INT
                                   ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/JAVA_INT
                                   ValueLayout/ADDRESS ValueLayout/JAVA_INT]))
          collect (.downcallHandle linker (.orElseThrow (.find lookup "collect_batch"))
                                   descriptor (into-array Linker$Option []))
          size-handle (.downcallHandle linker (.orElseThrow (.find lookup "facts_size"))
                                       (FunctionDescriptor/of ValueLayout/JAVA_INT
                                                              (into-array MemoryLayout []))
                                       (into-array Linker$Option []))
          _ (assert (= facts-bytes (invoke size-handle [])))
          controls (controls! collect arena)
          texts (sources baseline)
          workspace (pack! arena texts)
          _ (assert (= 0 (invoke collect (arguments workspace 1))))
          _ (doseq [index (range (:count workspace))]
              (assert (= (expected (nth texts index)) (decode workspace index))
                      (str "Source facts differ at " index)))
          native-arguments (arguments workspace 25)
          native-fn #(assert (= 0 (invoke collect native-arguments)))
          host-fn #(mapv expected texts)
          _ (native-fn)
          _ (host-fn)
          native (measure native-fn 5 25)
          host (measure host-fn 5 1)
          empty-arguments (assoc (arguments workspace 1) 4 (int 0))
          boundary (measure #(dotimes [_ 1000] (invoke collect empty-arguments)) 5 1000)
          result {:sources (:count workspace) :input-bytes (:total workspace)
                  :packing-ms (:packing-ms workspace) :native native :host host
                  :empty-panama-boundary boundary :controls controls
                  :all-source-facts-equal? true :guards-intact? (guard-intact? workspace)
                  :native-scan-allocations 0 :workspace-allocations 1
                  :profile :ReleaseFast :production-integrated? false}]
      (assert (:guards-intact? result))
      (spit output (pr-str result))
      (prn result)
      result)))

(defn -main [baseline library output]
  (try (benchmark! baseline library output) (finally (shutdown-agents))))
