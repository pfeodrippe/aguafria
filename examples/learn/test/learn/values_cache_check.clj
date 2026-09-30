(ns learn.values-cache-check
  "Explicit post-preparation check: evaluate values.clj body forms in a fresh JVM."
  (:require [aguafria.zig.explain :as explain]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io PushbackReader StringWriter]))

(defn -main [& _]
  (binding [runtime/*source-only-registration?* true]
    (require 'learn.example.values))
  (let [forms (with-open [reader (PushbackReader.
                                  (io/reader (io/resource "learn/example/values.clj")))]
                (doall (take-while some? (repeatedly #(read {:eof nil} reader)))))
        main (first (filter #(and (seq? %) (= 'az/defn (first %))
                                  (= 'main (second %))) forms))
        events (atom [])
        output (StringWriter.)
        started (System/nanoTime)]
    (assert main "values.clj must contain main")
    ;; Evaluate the actual lexical bodies as normal JVM Clojure, not native main.
    (binding [*ns* (the-ns 'learn.example.values)
              *out* output *err* output
              explain/*reporter* #(swap! events conj %)]
      (doseq [form (drop 4 main)]
        (eval form)))
    (let [expected ["1 + 1 = 2\n" "false\ntrue\nfalse\n"
                    "value: null\n" "value: hi\n"
                    "error union 1\ntype: error{ExampleErrorVariant}!i32\nvalue: error.ExampleErrorVariant\n"
                    "error union 2\ntype: error{ExampleErrorVariant}!i32\nvalue: 1234\n"]
          missing (filterv #(not (str/includes? (str output) %)) expected)
          result {:cache-dir (:cache-dir (runtime/configuration))
                  :body-forms (count (drop 4 main))
                  :output-verified? (empty? missing)
                  :duration-ms (/ (- (System/nanoTime) started) 1e6)
                  :events (frequencies (map :event @events))}]
      (print (str output))
      (prn result)
      (when (seq missing)
        (throw (ex-info "Cached values body forms produced incorrect output"
                        {:missing missing :result result})))
      (assert (zero? (get-in result [:events :compiled] 0))
              (pr-str (filter #(= :compiled (:event %)) @events))))
    (shutdown-agents)))
