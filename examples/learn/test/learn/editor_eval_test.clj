(ns learn.editor-eval-test
  (:require [aguafria.zig.repl :as repl]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [learn.jvm-assertions :as assertions]))

(def lessons
  [["values" 5]
   ["test_vector" 2]
   ["test_single_item_pointer" 4]
   ["test_pointer_arithmetic" 2]
   ["test_optional_type" 1]
   ["test_multidimensional_arrays" 4]])

(deftest planned-bodies-are-unmodified-ordinary-evaluations
  (doseq [[file expected] lessons]
    (testing file
      (let [resource (io/resource (str "learn/example/" file ".clj"))
            source (slurp resource)
            namespace (symbol (str "learn.example." (str/replace file "_" "-")))]
        (binding [*ns* *ns*]
          (clojure.lang.Compiler/load (java.io.StringReader. source) (.getPath resource) (.getPath resource)))
        (let [rows (:rows (repl/plan source (str namespace)))
              writer (java.io.StringWriter.)]
          (is (= expected (count rows)))
          (is (not-any? :status rows))
          (binding [*ns* (the-ns namespace) *file* (.getPath resource)
                    *out* writer *err* writer]
            (doseq [row rows]
              (assertions/call-with-checks #(eval (read-string (:expression row))))))
          (when (= file "values")
            (doseq [text ["1 + 1 = 2" "7.0 / 3.0 =" "false\ntrue\nfalse"
                          "optional 1" "optional 2" "error union 1" "error union 2"]]
              (is (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote text)) (str writer))))))
            (is (str/includes? (str writer) "value: hi"))
            (is (str/includes? (str writer) "value: 1234"))))))))
