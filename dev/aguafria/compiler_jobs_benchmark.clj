(ns aguafria.compiler-jobs-benchmark
  "Replay frozen native test builds to measure Zig's internal job count."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files]
           [java.util.concurrent Callable Executors TimeUnit]))

(set! *warn-on-reflection* true)

(defn- output-path [command]
  (some #(when (str/starts-with? % "-femit-bin=")
           (subs % (count "-femit-bin="))) command))

(defn plans [events directory jobs sample-count]
  (when-not (and (integer? sample-count) (<= 1 sample-count 20)
                 (or (nil? jobs) (and (integer? jobs) (<= 1 jobs 16))))
    (throw (ex-info "Expected a bounded sample and compiler job count"
                    {:sample-count sample-count :jobs jobs})))
  (let [commands (filter #(= :command (:kind %)) events)
        samples (vec (take sample-count
                           (filter #(= "test-obj" (second (:command %))) commands)))]
    (when-not (= sample-count (count samples))
      (throw (ex-info "Not enough recorded native test-object builds"
                      {:expected sample-count :actual (count samples)})))
    (mapv
     (fn [index event]
       (let [compile (:command event)
             object (output-path compile)
             link (:command (first (filter #(and (= "build-lib" (second (:command %)))
                                                 (= object (nth (:command %) 2 nil))) commands)))
             output (io/file directory (str index))
             new-object (str (io/file output "test.o"))
             library (str (io/file output (System/mapLibraryName "test")))
             rewrite (fn [command old new emit]
                       (into (if jobs [(first command) (second command) (str "-j" jobs)]
                                 [(first command) (second command)])
                             (map (fn [argument]
                                    (cond
                                      (and old (= argument old)) new
                                      (str/starts-with? argument "-femit-bin=")
                                      (str "-femit-bin=" emit)
                                      :else argument)))
                             (drop 2 command)))]
         (when-not (and object (seq link))
           (throw (ex-info "Missing matching native test linker command" {:index index})))
         {:directory output :compile (rewrite compile nil nil new-object)
          :link (rewrite link object new-object library)}))
     (range) samples)))

(defn- execute! [directory cache index command]
  (let [builder (ProcessBuilder. ^java.util.List command)
        environment (.environment builder)
        stdout (io/file directory (str index ".stdout"))
        stderr (io/file directory (str index ".stderr"))
        _ (.put environment "ZIG_GLOBAL_CACHE_DIR" (str (io/file cache "zig-global")))
        _ (.put environment "ZIG_LOCAL_CACHE_DIR" (str (io/file cache "zig-local")))
        _ (.directory builder ^java.io.File directory)
        _ (.redirectOutput builder stdout)
        _ (.redirectError builder stderr)
        started (System/nanoTime)
        process (.start builder)
        complete? (.waitFor process 60 TimeUnit/SECONDS)]
    (when-not complete?
      (.destroyForcibly process)
      (.waitFor process))
    {:command command :exit (.exitValue process) :timeout? (not complete?)
     :duration-ms (/ (- (System/nanoTime) started) 1e6)
     :stderr (slurp stderr)}))

(defn measure! [events directory jobs sample-count]
  (let [directory (.getAbsoluteFile (io/file directory))
        _ (Files/createDirectory (.toPath directory)
                                 (make-array java.nio.file.attribute.FileAttribute 0))
        plans (plans events directory jobs sample-count)
        _ (doseq [{:keys [directory]} plans]
            (Files/createDirectory (.toPath ^java.io.File directory)
                                   (make-array java.nio.file.attribute.FileAttribute 0)))
        cache-directory directory
        started (System/nanoTime)
        results
        (with-open [executor (Executors/newFixedThreadPool 4 (.factory (Thread/ofVirtual)))]
          (let [tasks (mapv (fn [{:keys [directory compile link]}]
                              (.submit executor
                                       ^Callable
                                       (fn []
                                         (let [compiled (execute! directory cache-directory 0 compile)]
                                           (if (zero? (:exit compiled))
                                             [compiled (execute! directory cache-directory 1 link)]
                                             [compiled]))))) plans)]
            (mapv #(.get ^java.util.concurrent.Future %) tasks)))
        report {:jobs jobs :concurrency 4 :samples sample-count
                :duration-ms (/ (- (System/nanoTime) started) 1e6)
                :results results :native-invocation-blocked? true
                :complete? (and (every? #(= 2 (count %)) results)
                                (every? #(zero? (:exit %)) (mapcat identity results)))}]
    (spit (io/file directory "result.edn") (pr-str report))
    report))

(defn -main [event-file directory]
  (let [directory (.getAbsoluteFile (io/file directory))
        _ (Files/createDirectory (.toPath directory)
                                 (make-array java.nio.file.attribute.FileAttribute 0))
        events (with-open [reader (io/reader event-file)]
                 (mapv edn/read-string (line-seq reader)))
        results (mapv #(measure! events (io/file directory (str "jobs-" (or % "default"))) % 4)
                      [nil 2 1 4])]
    (spit (io/file directory "results.edn") (pr-str results))
    (doseq [result results] (prn (dissoc result :results)))
    (when-not (every? :complete? results)
      (throw (ex-info "A recorded compiler replay failed" {})))))
