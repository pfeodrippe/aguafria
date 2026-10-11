(ns aguafria.zig.repl-test
  (:require [aguafria.zig.repl :as repl]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.charset StandardCharsets]
           [java.util Base64]))

(deftest planner-only-selects-complete-bodies-and-does-not-evaluate-source
  (let [source "(ns user)\n(def seen (atom []))\n(defn main []\n  (let [x (do (swap! seen conj :binding) (atom 0))]\n    (swap! x inc)\n    (if true @x (throw (Exception. \"wrong branch\"))))\n  (swap! seen conj :tail))\n(comment (main))"
        {:keys [rows]} (repl/plan source "user")]
    (is (= 2 (count rows)))
    (is (= [4 7] (mapv :line rows)))
    (is (= [6 7] (mapv :end-line rows)))
    (is (.startsWith (:expression (first rows)) "(let [x"))
    ;; No loading, calls, prefixes, session state, or print wrapper in the plan.
    (is (= "(swap! seen conj :tail)" (:expression (second rows))))
    (is (nil? (ns-resolve 'user 'seen)))))

(deftest literals-and-reader-syntax-have-exact-original-source-ranges
  (let [source "(ns user)\n(defn example []\n  42\n  #probe/value {:x 1}\n  #(+ % 1)\n  ^:marked [1 2]\n  #_ (must-not-run)\n  `(hello ~42)\n  ::unknown/tag)"
        reads (atom 0)
        rows (binding [*data-readers* {'probe/value (fn [x] (swap! reads inc) x)}]
               (:rows (repl/plan source "user")))]
    (is (= ["42" "#probe/value {:x 1}" "#(+ % 1)" "^:marked [1 2]"
            "`(hello ~42)" "::unknown/tag"] (mapv :expression rows)))
    (is (= [3 4 5 6 8 9] (mapv :line rows)))
    (is (every? #(and (:end-line %) (:end-column %)) rows))
    (is (zero? @reads))))

(deftest function-arguments-and-contracts-are-not-invented
  (let [rows (:rows (repl/plan
                     "(ns user) (defn args [x] x) (defn f ([] 42) ([x] x)) (defn checked [] {:pre [(valid?)]} 42)"
                     "user"))]
    (is (= [:requires-arguments nil :function-contract] (mapv :reason rows)))
    (is (= "42" (:expression (second rows))))))

(deftest namespace-aliases-resolve-against-the-loaded-namespace
  (let [namespace (gensym "aguafria.repl.alias")]
    (try
      (binding [*ns* (create-ns namespace)]
        (clojure.core/refer 'clojure.core)
        (alias 'c 'clojure.core))
      (is (= ["(let [x 1] (inc x))"]
             (mapv :expression (:rows (repl/plan
                                       "(ns unused) (c/defn main [] (let [x 1] (inc x)))"
                                       (str namespace))))))
      (finally (remove-ns namespace)))))

(deftest json-interface-transports-source-not-evaluated-values
  (let [request {:namespace "user" :source "(ns user) (defn main [] (str \"✓\" \"😀\"))"}
        encoded (.encodeToString (Base64/getEncoder)
                                 (.getBytes (json/write-str request) StandardCharsets/UTF_8))
        response (json/read-str (repl/plan-json encoded) :key-fn keyword)]
    (is (= "(str \"✓\" \"😀\")" (get-in response [:rows 0 :expression])))
    (is (not (contains? response :id)))
    (is (not (contains? response :value)))))
