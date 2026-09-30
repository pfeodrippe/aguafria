(ns learn.values-cache-check
  "Explicit post-preparation check: evaluate values.clj body forms in a fresh JVM."
  (:require [aguafria.zig.explain :as explain]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io])
  (:import [java.io PushbackReader]))

(defn -main [& _]
  (binding [runtime/*source-only-registration?* true]
    (require 'learn.example.values))
  (let [forms (with-open [reader (PushbackReader.
                                  (io/reader (io/resource "learn/example/values.clj")))]
                (doall (take-while some? (repeatedly #(read {:eof nil} reader)))))
        main (first (filter #(and (seq? %) (= 'az/defn (first %))
                                  (= 'main (second %))) forms))
        events (atom [])
        started (System/nanoTime)]
    (assert main "values.clj must contain main")
    ;; Evaluate the actual lexical bodies as normal JVM Clojure, not native main.
    (binding [*ns* (the-ns 'learn.example.values)
              explain/*reporter* #(swap! events conj %)]
      (doseq [form (drop 4 main)]
        (eval form)))
    (let [result {:cache-dir (:cache-dir (runtime/configuration))
                  :body-forms (count (drop 4 main))
                  :duration-ms (/ (- (System/nanoTime) started) 1e6)
                  :events (frequencies (map :event @events))}]
      (prn result)
      (assert (zero? (get-in result [:events :compiled] 0))
              (pr-str (filter #(= :compiled (:event %)) @events))))
    (shutdown-agents)))
