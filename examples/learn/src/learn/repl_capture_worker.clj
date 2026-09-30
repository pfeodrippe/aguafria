(ns learn.repl-capture-worker
  "Isolate intentionally failing native examples from the documentation builder."
  (:require [learn.reference :as reference]))

(defn -main [request-path result-path]
  (try
    (let [{:keys [source source-path]} (reference/read-edn request-path)
          result (reference/capture-comment-repl! source nil source-path)]
      (reference/write-edn! result-path result))
    (finally (shutdown-agents))))
