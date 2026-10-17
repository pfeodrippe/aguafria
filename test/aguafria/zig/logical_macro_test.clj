(ns aguafria.zig.logical-macro-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.logical-macro-fixture :as fixture]
            [clojure.test :refer [deftest is]]))

(a/defn tick :bool [[counter [:* :i32]] [result :bool]]
  (k/+= (a/deref counter) 1)
  result)

(deftest generated-logical-vars-are-native-macros-not-core-aliases
  (doseq [[native host form] [[#'k/and #'clojure.core/and '(k/and true false)]
                             [#'k/or #'clojure.core/or '(k/or false true)]]]
    (is (= 'aguafria.keyword (ns-name (:ns (meta native)))))
    (is (:macro (meta native)))
    (is (= :native-syntax (:aguafria/evaluation (meta native))))
    (is (not (identical? native host)))
    (is (not (identical? @native @host)))
    (let [expanded (binding [*ns* (the-ns 'aguafria.zig.logical-macro-test)]
                     (macroexpand-1 form))]
      (is (= '(clojure.core/requiring-resolve 'aguafria.zig.jvm/invoke-scoped!)
             (first expanded)))
      (is (= (list 'quote form) (nth expanded 2))))))

(deftest native-boolean-operators-capture-rather-than-eagerly-evaluate
  (is (:macro (meta #'k/and)))
  (is (:macro (meta #'k/or)))
  (doseq [[left expected-and expected-or calls-and calls-or]
          [[false false true 0 1] [true true true 1 0]]]
    (with-open [counter (k/var 0 :i32)]
      (is (= expected-and (a/value (k/and left (tick (k/& counter) true)))))
      (is (= calls-and @counter))
      (k/= counter 0)
      (is (= expected-or (a/value (k/or left (tick (k/& counter) true)))))
      (is (= calls-or @counter))))
  (with-open [counter (k/var 0 :i32)]
    (is (false? (a/value (k/and (tick (k/& counter) false)
                               (tick (k/& counter) true)))))
    (is (= 1 @counter)))
  (with-open [counter (k/var 0 :i32)]
    (with-redefs [tick (fn [& _] (throw (Exception. "JVM wrapper ran")))]
      (is (true? (a/value (k/and true (tick (k/& counter) true))))))
    (is (= 1 @counter)))
  ;; Zig can discard these invalid comptime operands; Clojure must never
  ;; evaluate their placeholder roots eagerly.
  (is (false? (a/value (k/and false (k/compileError "must not execute")))))
  (is (true? (a/value (k/or true (k/compileError "must not execute"))))))

(deftest qualified-and-referred-logical-macros-agree
  (doseq [left [false true] right [false true]]
    (is (= (and left right) (fixture/standalone left right)))
    (is (= (and left right) (a/value (k/and left right))))
    (is (= (or left right) (a/value (k/or left right)))))
  (let [and (fn [a b] [a b])]
    (is (= [:left :right] (and :left :right))))
  (is (= :host-value (clojure.core/or nil :host-value)))
  (is (identical? #'clojure.core/and (ns-resolve *ns* 'and))))

(deftest logical-operands-use-native-boolean-types
  (is (thrown? Exception (k/and 1 true)))
  (is (thrown? Exception (k/or false 1))))
