(ns aguafria.zig.consumer-try-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- forms [source]
  (binding [*read-eval* false]
    (with-open [reader (java.io.PushbackReader. (java.io.StringReader. source))]
      (doall (take-while #(not= ::eof %) (repeatedly #(read {:eof ::eof} reader)))))))

(defn- bare-propagation [source]
  (filter (fn [form]
            (and (seq? form) (= 'try (first form))
                 (not-any? #(and (seq? %) (#{'catch 'finally} (first %))) (rest form))))
          (tree-seq coll? seq (forms source))))

(deftest consumer-lessons-use-qualified-native-propagation
  (doseq [directory ["examples/learn/resources/learn/example"
                     "examples/learn/resources/learn/snippet"]
          file (file-seq (io/file directory))
          :when (and (.isFile file) (str/ends-with? (str file) ".clj"))]
    (let [source (slurp file)]
      (is (empty? (bare-propagation source)) (str file))
      (when (some #{'k/try} (tree-seq coll? seq (forms source)))
        (is (some #(and (vector? %) (= 'aguafria.keyword (first %))
                        (= :as (second %)) (= 'k (nth % 2 nil)))
                  (tree-seq coll? seq (forms source)))
            (str file " must require the keyword alias")))))
  (doseq [[zig {:keys [clojure-source]}]
          (edn/read-string (slurp "examples/learn/resources/learn/inline/operators.edn"))
          :when clojure-source]
    (is (empty? (bare-propagation clojure-source)) zig)))

(deftest qualified-try-preserves-native-emission-and-host-try-stays-distinct
  (binding [*ns* (the-ns 'aguafria.zig.consumer-try-test)]
    (is (= (a/emit-expr '(try (operation)))
           (a/emit-expr '(k/try (operation))))))
  (is (= :caught (try (throw (ex-info "Host failure" {}))
                      (catch clojure.lang.ExceptionInfo _ :caught))))
  (let [source (slurp "examples/learn/resources/learn/example/float_mode_exe.clj")]
    (is (some #(and (seq? %) (= 'try (first %))
                    (some (fn [child] (and (seq? child) (= 'finally (first child)))) (rest %)))
              (tree-seq coll? seq (forms source))))))
