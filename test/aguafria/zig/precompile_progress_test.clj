(ns aguafria.zig.precompile-progress-test
  (:require [aguafria.zig.discovery :as discovery]
            [aguafria.zig.precompile :as precompile]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.io StringWriter]
           [java.nio.file Files]))

(defn- with-report-file [f]
  (let [directory (.toFile (Files/createTempDirectory "aguafria-progress-"
                                                      (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (f (str (io/file directory "report.edn")))
      (finally
        (doseq [file (reverse (file-seq directory))]
          (io/delete-file file))))))

(deftest progress-is-plain-flushed-stderr
  (let [flushes (atom [])
        stderr (proxy [StringWriter] []
                 (flush [] (swap! flushes conj (.toString this))))
        stdout (StringWriter.)]
    (binding [*err* stderr *out* stdout *flush-on-newline* false]
      (#'precompile/progress! "Analyzing" "1/288" 'example.one))
    (is (= "" (str stdout)))
    (is (= ["Analyzing 1/288 example.one\n"] @flushes))))

(deftest analysis-progress-is-emitted-by-completion-not-submission-order
  (with-report-file
    (fn [report-file]
      (let [fast-printed (promise)
            stderr (proxy [StringWriter] []
                     (flush []
                       (when (str/includes? (.toString this) "Analyzing 1/2 progress.fast ")
                         (deliver fast-printed true))))]
        (with-redefs-fn
          {#'precompile/load-namespace! (fn [_ _] {})
           #'runtime/call-with-precompile-configuration (fn [_ f] (f))
           #'discovery/analyze! (fn [namespace]
                                  (when (= namespace 'progress.slow)
                                    (when-not (deref fast-printed 5000 false)
                                      (throw (ex-info "Progress was not flushed while a worker was blocked" {}))))
                                  {:namespace namespace :baseline {:exit 0}})
           #'discovery/prepare-observations! #(assoc % :status :prepared)}
          (fn []
            (binding [*err* stderr]
              (let [reports (#'precompile/analyze-namespaces!
                             '[progress.slow progress.fast] 2 report-file (atom {}))]
                (is (= [:prepared :prepared] (mapv :status reports)))
                (is (= '[progress.slow progress.fast] (mapv :namespace reports)))
                (is (= ["Loading 0/2"
                        "Loading 1/2 progress.slow"
                        "Loading 2/2 progress.fast"
                        "Analyzing 0/2"
                        "Analyzing 1/2 progress.fast"
                        "Analyzing 2/2 progress.slow"]
                       (mapv #(str/replace % #" \d+\.\d+s.*$" "") (str/split-lines (str stderr)))))
                (is (every? (fn [report]
                              (every? #(and (number? %) (not (neg? %)))
                                      (map (:timings report) [:load-ms :queued-ms :discovery-ms :preparation-ms :duration-ms])))
                            reports))
                (is (= (:timings (second reports))
                       (:timings (edn/read-string (slurp (str report-file ".d/progress.fast.edn"))))))))))))))

(deftest prior-durations-change-scheduling-not-report-order-or-work-selection
  (with-report-file
    (fn [report-file]
      (spit report-file (pr-str {:analysis [{:namespace 'progress.short :timings {:duration-ms 1}}
                                            {:namespace 'progress.long :timings {:duration-ms 1000}}]}))
      (let [order (atom [])]
        (with-redefs-fn
          {#'precompile/load-namespace! (fn [_ _] {})
           #'runtime/call-with-precompile-configuration (fn [_ f] (f))
           #'discovery/analyze! (fn [namespace]
                                  (swap! order conj namespace)
                                  {:namespace namespace :baseline {:exit 0}})
           #'discovery/prepare-observations! #(assoc % :status :prepared)}
          (fn []
            (binding [*err* (StringWriter.)]
              (let [report (#'precompile/analyze-namespaces!
                            '[progress.short progress.long] 1 report-file (atom {}))]
                (is (= '[progress.long progress.short] @order))
                (is (= '[progress.short progress.long] (mapv :namespace report)))
                (is (= [:prepared :prepared] (mapv :status report)))))))))))

(deftest progress-counts-processed-namespaces-not-only-successes
  (with-report-file
    (fn [report-file]
      (let [stderr (StringWriter.)
            namespaces '[progress.rejected progress.broken progress.skipped progress.load-failed]]
        (with-redefs-fn
          {#'precompile/load-namespace!
           (fn [namespace images]
             (case namespace
               progress.skipped (swap! images assoc (str namespace) {:reason :no-native-declarations})
               progress.load-failed (throw (ex-info "Load failed" {}))
               nil)
             {})
           #'runtime/call-with-precompile-configuration (fn [_ f] (f))
           #'discovery/analyze!
           (fn [namespace]
             (case namespace
               progress.rejected {:namespace namespace :baseline {:exit 1}}
               progress.broken (throw (ex-info "Analysis failed" {}))))}
          (fn []
            (binding [*err* stderr]
              (let [reports (#'precompile/analyze-namespaces! namespaces 4 report-file (atom {}))
                    lines (filter #(re-find #"^Analyzing [1-4]/4 " %) (str/split-lines (str stderr)))]
                (is (= [:rejected :analysis-failed :skipped :load-failed] (mapv :status reports)))
                (is (= ["1/4" "2/4" "3/4" "4/4"]
                       (mapv #(second (str/split % #" ")) lines)))
                (is (= (set (map str namespaces))
                       (set (map #(nth (str/split % #" ") 2) lines))))))))))))
