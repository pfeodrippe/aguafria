(ns aguafria.zig.declaration-grammar-test
  (:require [aguafria.zig :as a]
            [aguafria.zig.convert :as convert]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.repl :as repl]
            [aguafria.zig.runtime :as runtime]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest parameters-have-one-vector-entry-per-parameter
  (is (= [{:name 'x :type :i32 :properties {}}
          {:name 'y :type :f64 :properties {}}]
         (emitter/parse-typed-bindings '[[x :i32] [y :f64]])))
  (is (= [] (emitter/parse-typed-bindings [])))
  (is (= {:name 'T :type :type :properties {:zig/prefix "comptime"}}
         (first (emitter/parse-typed-bindings '[[T {:zig/prefix "comptime"} :type]]))))
  (doseq [bindings '[[x :- :i32 y :- :i32]
                     [x :i32 y :i32]
                     [[x :- :i32]]
                     [[x :i32] y :i32]]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (emitter/parse-typed-bindings bindings))
        (pr-str bindings)))
  (doseq [operator '[a/defn a/defn- a/defextern]
          bindings '[[x :- :i32] [x :i32]]]
    (is (thrown? Exception
                 (binding [*ns* (the-ns 'aguafria.zig.declaration-grammar-test)]
                   (macroexpand (list operator 'old :i32 bindings)))))))

(deftest constants-and-variables-use-the-same-type-first-grammar
  (doseq [operator '[a/defconst a/defvar const-decl var-decl]]
    (testing (str operator)
      (doseq [initializer [false nil "hello" {:x 1} [1 2]]]
        (is (= {:type nil :doc nil :attributes {} :value initializer}
               (emitter/parse-value-declaration (list operator 'x initializer)
                                                [initializer]))))
      (doseq [[tail expected]
              [['(:i32 42) {:type :i32 :doc nil :attributes {} :value 42}]
               ['(:i32 "doc" {:public true} 42)
                {:type :i32 :doc "doc" :attributes {:public true} :value 42}]
               ['(:i32 {:doc "metadata doc"} 42)
                {:type :i32 :doc "metadata doc" :attributes {:doc "metadata doc"} :value 42}]
               ['(:_ "doc" {:public true} "value")
                {:type nil :doc "doc" :attributes {:public true} :value "value"}]
               ['("doc" {:public true} "value")
                {:type nil :doc "doc" :attributes {:public true} :value "value"}]]]
        (is (= expected (emitter/parse-value-declaration (cons operator tail) tail))))
      (doseq [tail '[() ("doc" :i32 42) ({:public true} :i32 42)
                     ("doc" {:public true} :i32 42) (:- :i32 42)
                     (_ 42) (:i32 "doc" {:public true} 42 43)]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"explicit type must precede"
                              (emitter/parse-value-declaration (cons operator tail) tail)))))))

(deftest public-macros-retain-types-documentation-and-attributes
  (let [scratch (create-ns (gensym "aguafria.grammar-"))
        batch (runtime/registration-batch)]
    (try
      (binding [*ns* scratch runtime/*registration-batch* batch]
        (refer 'clojure.core)
        (require '[aguafria.zig :as a])
        (doseq [operator '[a/defconst a/defvar]]
          (let [name (symbol (str (clojure.core/name operator) "-value"))]
            (eval (list operator name :i32 "A typed declaration." {:public false} 42))
            (let [descriptor (last (runtime/collected-declarations batch))]
              (is (= :i32 (:type descriptor)))
              (is (= 42 (:value descriptor)))
              (is (= "A typed declaration." (:doc (meta (ns-resolve scratch name)))))
              (is (false? (:public? descriptor)))))
          (doseq [tail '[("doc" :i32 42) ({:public false} :i32 42)
                         ("doc" {:public false} :i32 42)]]
            (is (thrown? Exception (macroexpand (apply list operator 'old tail)))))))
      (finally (remove-ns (ns-name scratch))))))

(deftest nested-declarations-use-the-same-order-without-converter-reordering
  (doseq [[top nested] '[[a/defconst const-decl] [a/defvar var-decl]]]
    (let [form (with-meta (list top 'answer :i32 "doc" {:attrs #{:public}} 42) {:line 12})
          lowered (convert/nested-declaration-form form)
          source (emitter/emit-expr (list 'container {:kind :struct} [lowered]))]
      (is (= (cons nested (rest form)) lowered))
      (is (= {:line 12} (meta lowered)))
      (is (str/includes? source "answer: i32 = 42;")))
    (doseq [tail '[("doc" :i32 42) ({:attrs #{:public}} :i32 42)]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (emitter/emit-expr
                    (list 'container {:kind :struct} [(apply list nested 'old tail)])))))))

(deftest converter-produces-type-first-constants-and-variables
  (let [source (str "/// Constant docs.\npub const answer: i32 = 42;\n"
                    "pub var counter: u32 = 7;\n"
                    "pub const Box = struct {\n"
                    "/// Member docs.\npub const limit: u32 = 100;\n"
                    "pub var count: u32 = 0;\n"
                    "pub fn add(x: i32, y: i32) i32 { return x + y; }\n};")
        result (convert/convert-file "declaration-grammar.zig"
                                     {::convert/parsed (convert/parse-source source)
                                      :namespace 'fixture.declaration-grammar
                                      :declaration-docs {"counter" "Injected docs."}})
        declarations (filter #(and (seq? %) (symbol? (first %)))
                             (tree-seq coll? seq (:forms result)))
        named (fn [name] (first (filter #(= name (second %)) declarations)))
        answer (named 'answer)
        counter (named 'counter)
        limit (named 'limit)
        count-form (named 'count)
        add (named 'add)]
    (is (= [:i32 "Constant docs."] (vec (take 2 (drop 2 answer)))))
    (is (= [:u32 "Injected docs."] (vec (take 2 (drop 2 counter)))))
    (is (= [:u32 "Member docs."] (vec (take 2 (drop 2 limit)))))
    (is (= :u32 (nth count-form 2)))
    (is (map? (nth count-form 3)))
    (is (= '[[x :i32] [y :i32]] (first (filter vector? (drop 3 add)))))
    (is (not (str/includes? (:clojure-source result) ":zig/order")))))

(deftest editor-initializers-retain-type-context-after-documentation
  (let [source "(a/defconst answer :i32 \"doc\" {:public true} 42)\n(a/defvar count :u32 \"doc\" {:public true} 7)\n(a/defconst message \"hello\")\n(a/defconst inferred :_ \"doc\" {:public true} 8)"
        rows (:rows (repl/plan source 'aguafria.zig.declaration-grammar-test))]
    (is (= [":i32" ":u32" nil nil] (mapv :type-context rows)))
    (is (= ["42" "7" "\"hello\"" "8"] (mapv :expression rows)))))
