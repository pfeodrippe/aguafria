(ns aguafria.zig.switch-surface-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser]))

(defn- nodes [root]
  (tree-seq node/inner? node/children root))

(defn- sexpr [n]
  (try (node/sexpr n) (catch Throwable _ nil)))

(deftest learn-examples-use-the-current-switch-surface
  ;; Parse only: this must not load namespaces, run tests or compile applications.
  (let [directory (io/file "examples/learn/resources/learn/example")
        files (filter #(str/ends-with? (.getName %) ".clj") (file-seq directory))]
    (is (= 288 (count files)))
    (doseq [file files
            :let [source (slurp file)
                  forms (nodes (parser/parse-string-all source))]]
      (is (empty?
           (keep (fn [n]
                   (let [value (sexpr n)]
                     (cond
                       (and (= :token (node/tag n)) (keyword? value)
                            (str/starts-with? (name value) ".")) (node/string n)
                       (and (= :list (node/tag n)) (symbol? (first value))
                            (contains? #{"switch-stmt" "labeled-switch" "labeled-switch-stmt"
                                         "case-else" "inline-case" "inline-case-else"}
                                       (name (first value)))) (node/string n)
                       (and (= :meta (node/tag n))
                            (some #(contains? (meta value) %) [:capture :inline])) (node/string n))))
                 forms))
          (.getPath file)))))
