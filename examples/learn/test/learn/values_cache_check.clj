(ns learn.values-cache-check
  "Explicit post-preparation check: evaluate values.clj body forms in a fresh JVM."
  (:require [aguafria.zig.explain :as explain]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io PushbackReader StringWriter]))

(defn- native-libraries []
  (into #{} (comp (filter #(.isFile %))
                  (filter #(some (fn [suffix] (str/ends-with? (.getName %) suffix))
                                 [".dylib" ".so" ".dll"]))
                  (map #(.getAbsolutePath %)))
        (file-seq (io/file (:cache-dir (runtime/configuration))))))

(defn check!
  "Require the lesson normally and evaluate its JVM bodies with no new libraries."
  []
  (let [forms (with-open [reader (PushbackReader.
                                  (io/reader (io/resource "learn/example/values.clj")))]
                (doall (take-while some? (repeatedly #(read {:eof nil} reader)))))
        main (first (filter #(and (seq? %) (= 'a/defn (first %))
                                  (= 'main (second %))) forms))
        events (atom [])
        output (StringWriter.)
        native-output (StringWriter.)
        libraries-before (native-libraries)
        started (System/nanoTime)]
    (assert main "values.clj must contain main")
    ;; Evaluate the actual lexical bodies as normal JVM Clojure, not native main.
    (binding [*out* output *err* output
              explain/*reporter* #(swap! events conj %)]
      (require 'learn.example.values)
      (binding [*ns* (the-ns 'learn.example.values)]
        (doseq [form (drop 4 main)]
          (eval form)))
      (binding [*out* native-output *err* native-output]
        ((ns-resolve 'learn.example.values 'main))))
    (let [expected ["1 + 1 = 2\n" "false\ntrue\nfalse\n"
                    "value: null\n" "value: hi\n"
                    "error union 1\ntype: error{ExampleErrorVariant}!i32\nvalue: error.ExampleErrorVariant\n"
                    "error union 2\ntype: error{ExampleErrorVariant}!i32\nvalue: 1234\n"]
          missing (filterv #(not (str/includes? (str output) %)) expected)
          result {:cache-dir (:cache-dir (runtime/configuration))
                  :body-forms (count (drop 4 main))
                  :new-libraries (vec (remove libraries-before (native-libraries)))
                  :output-verified? (empty? missing)
                  :native-output-matches? (= (str native-output) (str output))
                  :duration-ms (/ (- (System/nanoTime) started) 1e6)
                  :events (frequencies (map :event @events))
                  :artifact-events (mapv #(select-keys % [:event :module :artifact-key :bundle-id])
                                         (filter :artifact-key @events))
                  :output (str output)}]
      (when (seq missing)
        (throw (ex-info "Cached values body forms produced incorrect output"
                        {:missing missing :result result})))
      (assert (zero? (get-in result [:events :compiled] 0))
              (pr-str (filter #(= :compiled (:event %)) @events)))
      (assert (empty? (:new-libraries result)) (pr-str (:new-libraries result)))
      (assert (:native-output-matches? result)
              (pr-str {:jvm-output (str output) :native-output (str native-output)}))
      result)))

(defn -main [& _]
  (let [result (check!)]
    (print (:output result))
    (prn (dissoc result :output)))
  (shutdown-agents))
