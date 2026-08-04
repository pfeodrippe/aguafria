(ns tigerbeetle-agua.generate
  "TigerBeetle conversion tooling, intentionally loadable before output exists."
  (:require [aguafria.zig.convert :as convert]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]))

(defn repository-root
  "Find this Aguafria checkout from the current working directory."
  []
  (or
   (some
    (fn [^java.io.File directory]
      (when (and (.isFile (io/file directory "src/aguafria/zig.clj"))
                 (.isDirectory
                  (io/file directory
                           "examples/tigerbeetle-agua/vendor/tigerbeetle")))
        (.getCanonicalFile directory)))
    (take-while some?
                (iterate #(.getParentFile ^java.io.File %)
                         (.getCanonicalFile
                          (io/file (System/getProperty "user.dir"))))))
   (throw
    (ex-info
     "Could not find the Aguafria checkout; pass explicit generation paths."
     {:user-dir (System/getProperty "user.dir")}))))

(defn project-paths
  "Return the default vendored Zig input, generated classpath, and report paths."
  []
  (let [root (repository-root)]
    {:repository-root (.getAbsolutePath root)
     :input-root (.getAbsolutePath
                  (io/file root
                           "examples/tigerbeetle-agua/vendor/tigerbeetle"))
     :output-root (.getAbsolutePath
                   (io/file root "examples/tigerbeetle-agua/generated"))
     :report-output (.getAbsolutePath
                     (io/file root
                              "examples/tigerbeetle-agua/generated/tigerbeetle-report.edn"))}))

(defn generated?
  "True when the generated TigerBeetle main namespace exists."
  ([] (generated? (:output-root (project-paths))))
  ([output-root]
   (.isFile
    (io/file output-root "tigerbeetle/src/tigerbeetle/main.clj"))))

(defn generation-summary
  "Keep the useful, small part of a convert-tree! report."
  [report]
  (select-keys report
               [:input-root
                :output-root
                :file-count
                :declaration-count
                :structural-declaration-count
                :raw-declaration-count
                :fallback-count
                :unresolved-syntax-count
                :build-profiles
                :elapsed-ms]))

(defn generate!
  "Generate Aguafria namespaces from TigerBeetle's vendored Zig tree."
  ([] (generate! {}))
  ([options]
   (let [paths (project-paths)
         input-root (or (:input-root options) (:input-root paths))
         output-root (or (:output-root options) (:output-root paths))
         report-output (or (:report-output options) (:report-output paths))
         convert-options
         (merge {:namespace-prefix 'tigerbeetle
                 :overwrite? true
                 :bundle-assets? true
                 :build-profiles [[] ["vopr"]]
                 :report-output report-output}
                (dissoc options :input-root :output-root))]
     (convert/convert-tree! input-root output-root convert-options))))

(defn ensure-generated!
  "Generate TigerBeetle only when its generated main namespace is absent."
  ([] (ensure-generated! {}))
  ([options]
   (let [output-root (or (:output-root options)
                         (:output-root (project-paths)))]
     (if (generated? output-root)
       {:status :present :output-root output-root}
       {:status :generated
        :report (generation-summary (generate! options))}))))

(defn -main
  [& [input-root output-root]]
  (try
    (pprint/pprint
     (generation-summary
      (generate! (cond-> {}
                   input-root (assoc :input-root input-root)
                   output-root (assoc :output-root output-root
                                      :report-output
                                      (str (io/file output-root
                                                    "conversion-report.edn")))))))
    (finally
      (shutdown-agents))))
