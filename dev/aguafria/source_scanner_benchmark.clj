(ns aguafria.source-scanner-benchmark
  "Full lexical facts and rewrites, measured against the retired JVM lexer."
  (:require [aguafria.native-collector-benchmark :as corpus]
            [aguafria.zig.source-scanner :as scanner]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; Frozen baseline oracle, used only in this developer benchmark.
(def baseline-token
  #"(?s)//[^\n]*|@\"(?:\\.|[^\"\\])*\"|\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|\\\\[^\n]*|[A-Za-z_][A-Za-z_0-9]*|\s+|.")

(defn baseline [source]
  (let [tokens (into [] (remove #(or (str/blank? %) (str/starts-with? % "//")))
                     (re-seq baseline-token source))
        declarations (filterv #(= "export" (first %)) (partition 3 1 tokens))]
    {:exports (mapv #(nth % 2) declarations)
     :external-exports? (boolean (some #(or (not= "fn" (second %))
                                            (not (re-matches #"__aguafria_[A-Za-z_0-9]+" (nth % 2))))
                                       declarations))
     :dynamic-exports? (boolean (some #(= ["@" "export"] (vec %)) (partition 2 1 tokens)))
     :external-declaration-syntax? (boolean (some #{"extern"} tokens))
     :imports (into [] (keep (fn [[at builtin open argument close after]]
                               (when (and (= "@" at) (= "(" open) (#{"import" "embedFile"} builtin))
                                 [builtin (when (or (= ")" close)
                                                    (and (= "," close) (= ")" after))) argument)])))
                    (partition-all 6 1 tokens))}))

(defn- measure [action]
  (let [samples (mapv (fn [_] (let [start (System/nanoTime)]
                                (action) (/ (- (System/nanoTime) start) 1e6))) (range 5))]
    {:samples-ms samples :mean-ms (/ (reduce + samples) (count samples))}))

(defn benchmark! [producer output]
  (let [sources (#'corpus/sources producer)
        old #(mapv baseline sources)
        native #(into [] (mapcat scanner/analyze-many!) (partition-all 16 sources))
        expected (old)
        actual (native)
        _ (doseq [[index a b] (map vector (range) expected actual)]
            (assert (= a (select-keys b (keys a)))
                    (pr-str {:index index :expected a :actual b})))
        _ (doseq [[source facts] (map vector sources actual)]
            (assert (= source (scanner/rewrite source facts {} nil))))
        result {:sources (count sources)
                :bytes (reduce + (map #(alength (.getBytes ^String % "UTF-8")) sources))
                :all-facts-match? true :verbatim-rewrites? true
                :baseline (measure old) :native-with-packing-and-decoding (measure native)
                :batch-size 16 :scan-native-allocations 0 :workspace-allocations 1}]
    (spit output (pr-str result))
    (prn result)
    result))

(defn -main [producer output cache]
  (when-not cache (throw (ex-info "Supply an isolated benchmark cache" {})))
  (runtime/configure! {:cache-dir cache})
  (try (benchmark! producer output) (finally (shutdown-agents))))
