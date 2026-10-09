(ns aguafria.validation-batch-benchmark
  "Compile-only experiment on already-generated, isolated bundle module graphs."
  (:require [aguafria.precompile-profile :as profile]
            [aguafria.zig.bundle :as bundle]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.util.concurrent Callable Executors]))

(defn- read-events [directory]
  (with-open [reader (io/reader (io/file directory "events.edn"))]
    (mapv edn/read-string (line-seq reader))))

(defn- semantic-command? [{:keys [kind command exit]}]
  (and (= :command kind) (zero? exit) (= "build-lib" (second command))
       (some #{"-fno-emit-bin"} command)))

(defn- response! [directory name arguments]
  (let [file (io/file directory name)]
    (spit file (str/join "\n" (map #'bundle/response-argument arguments)))
    (str "@" file)))

(defn- aggregate-arguments [events]
  (let [command (:command
                 (first (filter #(and (= :command (:kind %))
                                      (some #{'aguafria.zig.bundle/build-pack!} (:context %))
                                      (some (fn [arg] (str/starts-with? arg "@")) (:command %)))
                                events)))
        _ (assert command "The producer must have compiled one bundle")
        arguments (mapv edn/read-string (str/split-lines (slurp (subs (last command) 1))))]
    (assert (every? string? arguments))
    {:zig (first command)
     :arguments (mapv #(if (str/starts-with? % "-femit-bin=") "-fno-emit-bin" %) arguments)}))

(defn- run-commands! [state commands parallelism]
  (let [pool (Executors/newFixedThreadPool parallelism (.factory (Thread/ofVirtual)))
        started (System/nanoTime)]
    (try
      (let [futures (mapv (fn [command]
                            (.submit pool
                                     ^Callable
                                     (bound-fn []
                                       (#'profile/measured-sh state shell/sh command))))
                          commands)
            results (mapv #(.get ^java.util.concurrent.Future %) futures)]
        {:duration-ms (/ (- (System/nanoTime) started) 1e6)
         :commands (count commands) :exits (frequencies (map :exit results))
         :diagnostics (mapv :err (filter #(not (zero? (:exit %))) results))})
      (finally (.shutdownNow pool)))))

(defn benchmark! [baseline directory]
  (.mkdirs (io/file directory))
  (let [events (read-events baseline)
        individual (vec (distinct (map :command (filter semantic-command? events))))
        _ (assert (<= 1 (count individual) 2000) "Bounded ten-namespace benchmark")
        {:keys [zig arguments]} (aggregate-arguments events)
        command [zig "build-lib" (response! directory "aggregate.rsp" arguments)]
        positive-state (#'profile/recorder (str (io/file directory "aggregate")))
        ;; Both modes use the same completed producer sources and warm Zig cache.
        positive (run-commands! positive-state (repeat 3 command) 1)
        individual-state (#'profile/recorder (str (io/file directory "individual")))
        separate (run-commands! individual-state individual 4)
        root-argument (first (filter #(str/starts-with? % "-Mroot=") arguments))
        negative-file (io/file directory "negative.zig")
        _ (spit negative-file
                (str (slurp (subs root-argument 7))
                     "\ncomptime { @compileError(\"batch profile negative control\"); }\n"))
        negative-arguments
        (mapv #(if (= % root-argument) (str "-Mroot=" negative-file) %) arguments)
        negative-state (#'profile/recorder (str (io/file directory "negative")))
        negative (run-commands! negative-state
                                [[zig "build-lib" (response! directory "negative.rsp"
                                                             negative-arguments)]] 1)
        result {:individual separate :aggregate positive :negative negative
                :individual-resources (profile/summarize (vec (:events individual-state)))
                :aggregate-resources (profile/summarize (vec (:events positive-state)))
                :negative-resources (profile/summarize (vec (:events negative-state)))
                :native-code-executed? false :aguafria-cache-publication? false}]
    (assert (= {0 (count individual)} (:exits separate)))
    (assert (= {0 3} (:exits positive)))
    (assert (= {1 1} (:exits negative)))
    (assert (str/includes? (first (:diagnostics negative)) "batch profile negative control"))
    (spit (io/file directory "result.edn") (pr-str result))
    (prn result)
    result))

(defn -main [baseline directory]
  (try (benchmark! baseline directory) (finally (shutdown-agents))))
