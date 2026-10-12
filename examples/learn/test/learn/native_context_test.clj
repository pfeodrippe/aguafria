(ns learn.native-context-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [clojure.test :refer [deftest is]]
            [learn.native-context :as context]))

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

(deftest failed-native-context-results-are-not-passes
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"returned an error"
                       (context/check-result! {:kind :native-context} {:error :TestUnexpectedResult})))
  (is (= {:ok nil} (context/check-result! {:kind :native-context} {:ok nil})))
  (is (= {:error :ApplicationData} (context/check-result! {} {:error :ApplicationData}))))
