(ns learn.native-context-test
  (:require [aguafria.builtin]
            [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [clojure.test :refer [deftest is]]
            [learn.native-context :as context]))

(deftest returns-cross-blocks-but-not-nested-functions-or-quotes
  (let [ns (the-ns 'learn.native-context-test)]
    (is (context/requires-function-scope? ns '(a/block (k/return 1))))
    (is (not (context/requires-function-scope? ns '(fn [] (k/return 1)))))
    (is (not (context/requires-function-scope? ns '(quote (k/return 1)))))))

(deftest native-entry-selection-traces-context-not-application-values
  (let [ns (create-ns (gensym "learn.entry-context-")) calls (atom 0)
        install (fn [name declaration]
                  (intern ns (with-meta name {:aguafria/declaration
                                              (assoc declaration :module (str (ns-name ns)) :name name)})
                          #(swap! calls inc)))]
    (try
      (install 'helper {:kind :fn :return :bool :args [] :body '[aguafria.builtin/is_test]})
      (install 'native-test {:kind :test :args [] :body '[(helper)]})
      (install 'fallible {:kind :fn :return :!i32 :args [] :body '[(aguafria.zig/error-value :Failure)]})
      (install 'main {:kind :fn :return :void :args [] :body '[(fallible)]})
      (is (= [(symbol (str (ns-name ns)) "native-test")] (context/native-test-entries ns)))
      (is (context/requires-function-scope? ns '(fallible)))
      (is (zero? @calls))
      (finally (remove-ns (ns-name ns))))))

(deftest cleanup-needs-an-enclosing-native-context-not-a-source-rewrite
  (let [ns (the-ns 'learn.native-context-test)]
    (is (context/requires-function-scope? ns '(k/defer (cleanup))))
    (is (context/requires-function-scope? ns '(let [x 1] (k/defer (use x)))))
    (is (not (context/requires-function-scope? ns '(a/block (k/defer (cleanup))))))
    (is (not (context/requires-function-scope? ns '(quote (k/defer (cleanup))))))
    (is (not (context/requires-function-scope? ns '(k/for [x items] (k/defer (use x))))))))

(deftest context-selection-does-not-execute-or-replay-individual-defers
  (let [ns (create-ns (gensym "learn.native-context-fixture-"))
        calls (atom 0)
        body '[(aguafria.keyword/defer (cleanup)) (work)]
        rows [{:declaration "main" :line 10 :expression "(k/defer (cleanup))"}
              {:declaration "main" :line 11 :expression "(work)"}]]
    (try
      (intern ns (with-meta 'main {:aguafria/declaration {:kind :fn :args [] :body body}})
              #(swap! calls inc))
      (let [planned (context/execution-rows ns rows)]
        (is (zero? @calls))
        (is (= 1 (count planned)))
        (is (= :native-context (:kind (first planned))))
        (is (= "(main)" (:expression (first planned))))
        (is (= rows (:source-body-rows (first planned))))
        (is (true? (:generated-invocation? (first planned)))))
      (alter-meta! (ns-resolve ns 'main) assoc-in [:aguafria/declaration :args] [{:name 'input :type :i32}])
      (is (= rows (context/execution-rows ns rows)))
      (alter-meta! (ns-resolve ns 'main) dissoc :aguafria/declaration)
      (is (= rows (context/execution-rows ns rows)))
      (finally (remove-ns (ns-name ns))))))

(deftest compiler-directive-context-is-planned-without-running-the-body
  (let [ns (create-ns (gensym "learn.compiler-context-fixture-"))
        calls (atom 0)
        body '[(aguafria.keyword/comptime (aguafria.keyword/setEvalBranchQuota 1001))]
        rows [{:declaration "check" :line 3 :expression (pr-str (first body))}]]
    (try
      (intern ns (with-meta 'check {:aguafria/declaration {:kind :test :args [] :body body}})
              #(swap! calls inc))
      (let [planned (first (context/execution-rows ns rows))]
        (is (zero? @calls))
        (is (= :function-scope-compiler-directive (:context-reason planned)))
        (is (= "(check)" (:expression planned)))
        (is (= rows (:source-body-rows planned))))
      (is (not (context/requires-function-scope? ns
                                                 '(aguafria.zig/block (aguafria.keyword/setEvalBranchQuota 1001)))))
      (is (not (context/requires-function-scope? ns
                                                 '(quote (aguafria.keyword/setEvalBranchQuota 1001)))))
      (finally (remove-ns (ns-name ns))))))

(deftest private-helpers-are-not-executed-before-their-authored-test
  (let [ns (create-ns (gensym "learn.private-helper-fixture-"))
        calls (atom 0)
        rows [{:declaration "helper" :line 1 :expression "(mutate-state)"}
              {:declaration "check" :line 2 :expression "(helper)"}
              {:declaration "unused" :line 3 :expression "42"}]]
    (try
      (doseq [[name metadata] [['helper {:private true :aguafria/declaration {:kind :fn :args [] :body '[(helper)]}}]
                               ['check {:aguafria/declaration {:kind :test :args [] :body '[(helper)]}}]
                               ['unused {:private true :aguafria/declaration {:kind :fn :args [] :body [42]}}]]]
        (intern ns (with-meta name metadata) #(swap! calls inc)))
      (let [planned (context/execution-rows ns rows)]
        (is (zero? @calls))
        (is (= :private-helper-called-by-native-entry (:reason (first planned))))
        (is (= :skipped (:status (first planned))))
        (is (= (rest rows) (rest planned))))
      (alter-meta! (ns-resolve ns 'check) assoc-in [:aguafria/declaration :body]
                   '[(let [helper 42] (helper))])
      (is (= rows (context/execution-rows ns rows)))
      (alter-meta! (ns-resolve ns 'check) assoc-in [:aguafria/declaration :body]
                   '[(quote (helper))])
      (is (= rows (context/execution-rows ns rows)))
      (finally (remove-ns (ns-name ns))))))

(deftest namespace-initializers-use-the-compiled-owner-not-runtime-replay
  (let [ns (create-ns (gensym "learn.initializer-context-fixture-"))
        row {:declaration "answer" :kind :initializer :line 1
             :type-context ":i32" :expression "(must-not-replay)"}]
    (try
      (intern ns (with-meta 'answer {:aguafria/declaration {:kind :const}}) 42)
      (let [planned (first (context/execution-rows ns [row]))]
        (is (= :namespace-initialization (:context-reason planned)))
        (is (= row (:source-initializer planned)))
        (is (not (contains? planned :type-context)))
        (is (= 42 (binding [*ns* ns] (eval (read-string (:expression planned)))))))
      (finally (remove-ns (ns-name ns))))))

(deftest failed-native-context-results-are-not-passes
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"returned an error"
                        (context/check-result! {:kind :native-context} {:error :TestUnexpectedResult})))
  (is (= {:ok nil} (context/check-result! {:kind :native-context} {:ok nil})))
  (is (= {:error :ApplicationData} (context/check-result! {} {:error :ApplicationData}))))
