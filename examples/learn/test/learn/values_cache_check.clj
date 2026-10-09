(ns learn.values-cache-check
  "Explicit post-preparation check: evaluate values.clj body forms in a fresh JVM."
  (:require [aguafria.zig.explain :as explain]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io PushbackReader StringWriter]))

(defn- native-libraries []
  (into #{} (comp (filter #(.isFile %))
                  (filter #(some (fn [suffix] (str/ends-with? (.getName %) suffix))
                                 [".dylib" ".so" ".dll"]))
                  (map #(.getAbsolutePath %)))
        (file-seq (io/file (:cache-dir (runtime/configuration))))))

(defn validate-report!
  "Require correct output and reuse of the exact pack from preparation."
  [{:keys [body-forms new-libraries output-verified? native-output-matches?
           producer-bundle-id bundles events standalone] :as report}]
  (when-not (and (= 5 body-forms) output-verified? native-output-matches?)
    (throw (ex-info "Values bodies did not match the native lesson" {:result report})))
  (when (or (seq new-libraries) (seq standalone)
            (pos? (get events :compiled 0))
            (pos? (get events :compile-failed 0)))
    (throw (ex-info "Values bodies missed the prepared bundle" {:result report})))
  (when-not (and (string? producer-bundle-id)
                 (= #{producer-bundle-id} bundles)
                 (= 1 (:bundle-loaded events)))
    (throw (ex-info "Values did not load exactly the producer pack" {:result report})))
  report)

(defn check!
  "Evaluate all five actual Values JVM bodies using the supplied producer pack."
  [producer-report]
  (when (find-ns 'learn.example.values)
    (throw (ex-info "Run the Values cache check in a fresh JVM" {})))
  (let [producer (edn/read-string (slurp producer-report))
        _ (when-not (= 1 (count (get-in producer [:bundles :packs])))
            (throw (ex-info "Producer report must contain one bundle"
                            {:producer-report producer-report})))
        forms (with-open [reader (PushbackReader.
                                  (io/reader (io/resource "learn/example/values.clj")))]
                (doall (take-while some? (repeatedly #(read {:eof nil} reader)))))
        main (first (filter #(and (seq? %) (= 'a/defn (first %))
                                  (= 'main (second %))) forms))
        events (atom [])
        body-timings (atom [])
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
        (doseq [[index form] (map-indexed vector (drop 4 main))]
          (let [body-started (System/nanoTime)]
            (eval form)
            (swap! body-timings conj
                   {:index index
                    :duration-ms (/ (- (System/nanoTime) body-started) 1e6)}))))
      (binding [*out* native-output *err* native-output]
        ((ns-resolve 'learn.example.values 'main))))
    (let [expected ["1 + 1 = 2\n" "false\ntrue\nfalse\n"
                    "value: null\n" "value: hi\n"
                    "error union 1\ntype: error{ExampleErrorVariant}!i32\nvalue: error.ExampleErrorVariant\n"
                    "error union 2\ntype: error{ExampleErrorVariant}!i32\nvalue: 1234\n"]
          missing (filterv #(not (str/includes? (str output) %)) expected)
          result {:cache-dir (:cache-dir (runtime/configuration))
                  :producer-report producer-report
                  :producer-bundle-id (get-in producer [:bundles :packs 0 :id])
                  :body-forms (count (drop 4 main))
                  :body-timings @body-timings
                  :new-libraries (vec (remove libraries-before (native-libraries)))
                  :output-verified? (empty? missing)
                  :native-output-matches? (= (str native-output) (str output))
                  :duration-ms (/ (- (System/nanoTime) started) 1e6)
                  :events (frequencies (map :event @events))
                  :bundles (set (keep :bundle-id @events))
                  :standalone (filterv #(and (= :disk-cache-hit (:event %))
                                             (str/starts-with? (str (:module %))
                                                               "aguafria.jvm."))
                                       @events)
                  :artifact-events (mapv #(select-keys % [:event :module :artifact-key :bundle-id])
                                         (filter :artifact-key @events))
                  :output (str output)}]
      (when (seq missing)
        (throw (ex-info "Cached values body forms produced incorrect output"
                        {:missing missing :result result})))
      (validate-report! result))))

(defn -main [producer-report & _]
  (when-not producer-report
    (throw (ex-info "Supply the Learn precompile report path" {})))
  (try
    (let [result (check! producer-report)]
      (print (:output result))
      (prn (dissoc result :output)))
    (finally (shutdown-agents))))
