(ns learn.jvm-body-test
  "Evaluate the authored test bodies as ordinary JVM code, not native tests."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [learn.jvm-audit :as audit]
            [learn.jvm-assertions :as assertions]))

(defn evaluate-test-bodies! [source]
  (let [resource (io/resource source)
        forms (audit/read-forms (slurp resource))
        namespace (second (first forms))]
    (require namespace)
    (binding [*ns* (the-ns namespace)
              *file* (.getPath resource)]
      (mapv (fn [[_ test-name & body]]
              (assertions/call-with-checks
               #(eval (cons 'do (drop-while (fn [form] (or (string? form) (map? form))) body))))
              test-name)
            (filter #(and (seq? %) (symbol? (first %))
                          (= "deftest" (name (first %)))) forms)))))

(deftest pointer-and-vector-bodies-execute-directly
  (doseq [[source expected]
          [["learn/example/test_single_item_pointer.clj" 3]
           ["learn/example/test_pointer_arithmetic.clj" 2]
           ["learn/example/test_vector.clj" 2]]]
    (testing source
      (is (= expected (count (evaluate-test-bodies! source)))))))

(deftest values-main-body-executes-directly
  (let [resource (io/resource "learn/example/values.clj")
        forms (audit/read-forms (slurp resource))
        namespace (second (first forms))
        main (first (filter #(and (seq? %) (= 'a/defn (first %))
                                  (= 'main (second %))) forms))
        body (rest (drop-while #(not (vector? %)) (drop 2 main)))]
    (require namespace)
    (binding [*ns* (the-ns namespace)
              *file* (.getPath resource)]
      (let [capture (fn [invoke]
                      (let [writer (java.io.StringWriter.)]
                        (binding [*out* writer *err* writer] (invoke))
                        (str writer)))
            native-output (capture #((ns-resolve namespace 'main)))
            jvm-output (capture #(assertions/call-with-checks (fn [] (eval (cons 'do body)))))]
        (is (not (empty? native-output)))
        (is (= native-output jvm-output))))))

(deftest values-subforms-execute-directly
  (let [source "learn/example/values.clj"
        inventory (audit/plan (slurp (io/resource source)))
        result (audit/audit-example!
                {:source source :zig "values.zig" :inventory inventory}
                {:kinds (set (map :kind (:cases inventory)))})
        runnable (remove :status (:cases inventory))]
    (is (seq runnable))
    (doseq [[planned actual] (map vector (:cases inventory) (:cases result))]
      (testing (str (:path planned) " " (pr-str (:expression planned)))
        (if (:status planned)
          ;; In this lesson these are literal boolean forms and error-set
          ;; schemas: no missing local bindings or branch-dependent effects.
          ;; Evaluate and print them too; a newly unresolvable form must fail.
          (binding [*ns* (the-ns (:namespace inventory))]
            (is (string? (pr-str (eval (:expression planned))))))
          (is (= :passed (:status actual))
              (pr-str (select-keys actual [:message :cause :printed-value]))))))))
