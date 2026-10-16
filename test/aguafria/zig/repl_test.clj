(ns aguafria.zig.repl-test
  (:require [aguafria.zig :as a]
            [aguafria.zig.repl :as repl]
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

(deftest multiline-docstrings-do-not-become-function-arguments
  (let [rows (:rows (repl/plan
                     (str "(a/defn main :void \"First line\nSecond line\" [] (run-main))\n"
                          "(defn helper \"First line\nSecond line\" [] 42)\n"
                          "(a/defconst typed :i32 \"First line\nSecond line\" (value))")
                     "aguafria.zig.repl-test"))]
    (is (= ["(run-main)" "42" "(value)"] (mapv :expression rows)))
    (is (not-any? :reason rows))
    (is (= ":i32" (:type-context (last rows))))))

(deftest native-noreturn-bodies-are-not-standalone-editor-evaluations
  (let [rows (:rows (repl/plan
                     (str "(a/defn- stop :noreturn [] (must-not-run))\n"
                          "(a/defn entry :noreturn {:zig/qualifiers \"callconv(.naked)\"} [] (stop))\n"
                          "(defn stop [] 42)\n"
                          "(a/deftest ordinary (check-result))")
                     "aguafria.zig.repl-test"))]
    (is (= [:non-returning-function :non-returning-function nil nil] (mapv :reason rows)))
    (is (= [:skipped :skipped nil nil] (mapv :status rows)))
    (is (= ["42" "(check-result)"] (mapv :expression (drop 2 rows))))))

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

(deftest native-initializers-are-whole-forms-with-declaration-context
  (let [source (str "(a/defconst native-endian ((:endian (:arch (:cpu builtin/target)))))\n"
                    "(a/defconst text \"literal value\")\n"
                    "(a/defconst typed :i32 \"doc\" {:private true} (k/intCast source))\n"
                    "(a/defvar counter :u32 \"doc\" {:private true} (k/+ one two))\n"
                    "(def ordinary (must-not-run-again))")
        rows (:rows (repl/plan source "aguafria.zig.repl-test"))]
    (is (= 4 (count rows)))
    (is (every? #(= :initializer (:kind %)) rows))
    (is (= ["((:endian (:arch (:cpu builtin/target))))" "\"literal value\""
            "(k/intCast source)" "(k/+ one two)"] (mapv :expression rows)))
    (is (= [nil nil ":i32" ":u32"] (mapv :type-context rows)))
    (is (= [1 2 3 4] (mapv :line rows)))
    (is (= [27 18 46 46] (mapv :column rows)))))

(deftest json-interface-transports-source-not-evaluated-values
  (let [request {:namespace "user" :source "(ns user) (defn main [] (str \"✓\" \"😀\"))"}
        encoded (.encodeToString (Base64/getEncoder)
                                 (.getBytes (json/write-str request) StandardCharsets/UTF_8))
        response (json/read-str (repl/plan-json encoded) :key-fn keyword)]
    (is (= "(str \"✓\" \"😀\")" (get-in response [:rows 0 :expression])))
    (is (not (contains? response :id)))
    (is (not (contains? response :value)))))
