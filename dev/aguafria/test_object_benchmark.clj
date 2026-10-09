(ns aguafria.test-object-benchmark
  "Compare real Zig test code generation without executing test bodies."
  (:refer-clojure :exclude [run!])
  (:require [aguafria.zig.artifact :as artifact]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(set! *warn-on-reflection* true)

(defn- recorded-test [events]
  (first (filter #(and (= :command (:kind %))
                       (= "test" (second (:command %)))
                       (some (fn [arg] (str/starts-with? arg "-femit-llvm-bc="))
                             (:command %)))
                 events)))

(defn- argument-value [command prefix]
  (some #(when (str/starts-with? % prefix) (subs % (count prefix))) command))

(defn commands [events directory route]
  (let [test-command (:command (recorded-test events))
        old-bitcode (argument-value test-command "-femit-llvm-bc=")
        link-command (:command (first (filter #(and (= :command (:kind %))
                                                    (= "build-lib" (second (:command %)))
                                                    (= old-bitcode (nth (:command %) 2 nil)))
                                              events)))
        object? (= route :llvm-object)
        payload (str (io/file directory (if object? "test.o" "test.bc")))
        library (str (io/file directory (System/mapLibraryName "test")))
        compile-command
        (mapv (fn [arg]
                (cond
                  (and object? (= arg "test")) "test-obj"
                  (str/starts-with? arg "-femit-llvm-bc=")
                  (str (if object? "-femit-bin=" "-femit-llvm-bc=") payload)
                  :else arg))
              (if object? (remove #{"-fno-emit-bin"} test-command) test-command))
        link-command (mapv (fn [arg]
                             (cond
                               (= arg old-bitcode) payload
                               (str/starts-with? arg "-femit-bin=")
                               (str "-femit-bin=" library)
                               :else arg)) link-command)]
    (when-not (and old-bitcode (seq link-command) (#{:bitcode :llvm-object} route))
      (throw (ex-info "Expected recorded test/bitcode build and a supported route" {:route route})))
    {:commands [compile-command link-command
                ["dsymutil" "--flat" "--num-threads" "1" library "-o" (str library ".dwarf")]]
     :library-path library :payload-path payload}))

(defn- run-command! [directory cache-directory index command]
  (let [out-file (io/file directory (str index ".stdout"))
        err-file (io/file directory (str index ".stderr"))
        builder (ProcessBuilder. ^java.util.List command)
        environment (.environment builder)
        _ (.put environment "ZIG_GLOBAL_CACHE_DIR" (str (io/file cache-directory "zig-global")))
        _ (.put environment "ZIG_LOCAL_CACHE_DIR" (str (io/file cache-directory "zig-local")))
        _ (.directory builder (io/file directory))
        _ (.redirectOutput builder out-file)
        _ (.redirectError builder err-file)
        started (System/nanoTime)
        process (.start builder)
        completed? (.waitFor process 60 TimeUnit/SECONDS)]
    (when-not completed?
      (.destroyForcibly process)
      (.waitFor process))
    {:command command :exit (.exitValue process) :timeout? (not completed?)
     :duration-ms (/ (- (System/nanoTime) started) 1e6)
     :stdout (slurp out-file) :stderr (slurp err-file)}))

(defn run! [event-file directory route]
  (let [directory (.getAbsoluteFile (io/file directory))
        _ (when (.exists directory)
            (throw (ex-info "Benchmark requires a new isolated directory" {:directory (str directory)})))
        _ (.mkdirs directory)
        events (mapv edn/read-string (str/split-lines (slurp event-file)))
        {:keys [commands library-path payload-path]} (commands events directory route)
        results (loop [pending commands results []]
                  (if (and (seq pending) (or (empty? results) (zero? (:exit (peek results)))))
                    (let [result (run-command! directory directory (count results) (first pending))]
                      (spit (io/file directory (str (count results) ".edn"))
                            (artifact/print-data result))
                      (recur (next pending) (conj results result)))
                    results))
        report {:route route :commands results :native-invocation-blocked? true
                :library-path library-path :payload-path payload-path
                :duration-ms (reduce + (map :duration-ms results))
                :complete? (and (= 3 (count results)) (every? #(zero? (:exit %)) results))}]
    (spit (io/file directory "result.edn") (artifact/print-data report))
    (prn report)
    report))

(defn run-sample! [event-file directory route sample-count]
  (when-not (<= 1 sample-count 20)
    (throw (ex-info "Expected a bounded sample count" {:count sample-count})))
  (let [directory (.getAbsoluteFile (io/file directory))
        _ (when (.exists directory)
            (throw (ex-info "Benchmark requires a new isolated directory" {:directory (str directory)})))
        _ (.mkdirs directory)
        events (mapv edn/read-string (str/split-lines (slurp event-file)))
        samples (take sample-count
                      (filter #(and (= :command (:kind %))
                                    (= "test" (second (:command %)))
                                    (some (fn [arg] (str/starts-with? arg "-femit-llvm-bc="))
                                          (:command %))) events))
        results
        (mapv (fn [index sample]
                (let [output (doto (io/file directory (str index)) .mkdirs)
                      {:keys [commands library-path]} (commands (cons sample events) output route)
                      measurements (mapv #(run-command! output directory %1 %2)
                                         (range) commands)
                      result {:library-path library-path :commands measurements
                              :complete? (every? #(zero? (:exit %)) measurements)
                              :duration-ms (reduce + (map :duration-ms measurements))}]
                  (spit (io/file output "result.edn") (artifact/print-data result))
                  result)) (range) samples)
        report {:route route :results results :native-invocation-blocked? true
                :duration-ms (reduce + (map :duration-ms results))
                :complete? (and (= sample-count (count results)) (every? :complete? results))}]
    (spit (io/file directory "result.edn") (artifact/print-data report))
    report))
