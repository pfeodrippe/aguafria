(ns aguafria.zig.explicit-operator-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.project :as project]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.referred-operator-fixture :as fixture]
            [clojure.test :refer [deftest is]]))

(def context (the-ns 'aguafria.zig.referred-operator-fixture))

(deftest host-first-namespaces-keep-clojure-defaults
  (is (identical? #'clojure.core/+ (ns-resolve 'aguafria.zig.explicit-operator-test '+)))
  (is (identical? #'clojure.core/or (ns-resolve 'aguafria.zig.explicit-operator-test 'or)))
  (is (= 5 (+ 2 3)))
  (is (= 5 (a/value (k/comptime (k/+ 2 3))))))

(def host-context
  (let [n (create-ns 'aguafria.zig.operator-host-context)]
    (binding [*ns* n]
      (refer 'clojure.core)
      (alias 'a 'aguafria.zig))
    n))

(deftest native-operators-do-not-reinterpret-host-vars
  (doseq [spelling (distinct (concat (map :zig-token (k/reader-tokens))
                                     (map :name (k/reader-tokens))
                                     ["and" "or" "catch" "orelse" "mod" ".."]))
          head [(symbol spelling) (symbol "clojure.core" spelling)]
          emit [emitter/emit-expr emitter/emit-stmt]]
    (binding [emitter/*keyword-context* host-context]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must resolve to aguafria.keyword Vars"
                            (emit (list head 1 2)))
          (str head))))
  (doseq [body '[(+ x 1) (* x 2) (and true false) (or false true)
                 (clojure.core/+ x 1) (+= x 1)]]
    (is (thrown? Exception
                 (binding [*ns* host-context]
                   (macroexpand (list 'a/defn 'invalid :i32 '[[x :i32]] body)))))))

(deftest explicit-vars-preserve-native-operator-emission
  (binding [emitter/*keyword-context* context]
    (doseq [[form expected] [['(k/+ 2 3) "(2 + 3)"]
                             ['(k/* 2 3) "(2 * 3)"]
                             ['(k/and true false) "(true and false)"]
                             ['(k/or false true) "(false or true)"]
                             ['(k/mod 7 3) "@mod(7, 3)"]
                             ['(k/orelse optional 3) "(optional orelse 3)"]
                             ['(k/catch result 3) "(result catch 3)"]]]
      (is (= expected (emitter/emit-expr form)))
      (is (= expected (emitter/emit-expr (emitter/qualify-form context form)))))
    (is (= "x += 1;" (emitter/emit-stmt '(k/+= x 1)))))
  ;; No changes to ordinary host arithmetic or short-circuiting.
  (is (= 5 (+ 2 3)))
  (is (= 6 (* 2 3)))
  (is (= :value (or false :value))))

(deftest actual-native-functions-are-not-reinterpreted-by-their-spelling
  (doseq [spelling ["+" "+=" "and"]]
    (let [n (create-ns (gensym "aguafria.native-operator-name-"))
          op (symbol (str (ns-name n)) spelling)
          v (intern n (symbol spelling))]
      (try
        (alter-meta! v assoc :aguafria/zig-reference
                     {:kind :declaration :module (str (ns-name n))
                      :symbol op :zig-name "native_add"})
        (binding [emitter/*keyword-context* n]
          (is (= "native_add(1, 2)" (emitter/emit-expr (list op 1 2))))
          (is (= "return native_add(1, 2);"
                 (emitter/emit-function-body [(list op 1 2)] :i32))))
        (finally (remove-ns (ns-name n)))))))

(deftest registered-native-declarations-do-not-become-operators
  (with-redefs [project/declaration-source-order (fn [_ spelling] (when (= "mod" spelling) 0))]
    (binding [emitter/*keyword-context* host-context]
      (is (= "mod(7, 3)" (emitter/emit-expr '(mod 7 3))))
      (is (thrown? Exception (emitter/emit-expr '(clojure.core/mod 7 3)))))))

(deftest referred-operators-are-the-same-native-vars
  (is (identical? (ns-resolve context '+) #'k/+))
  (is (identical? (ns-resolve context 'or) #'k/or))
  (is (not-any? (fn [[_ v]] (= 'clojure.core (ns-name (:ns (meta v)))))
                (ns-refers context)))
  (binding [emitter/*keyword-context* context]
    (doseq [[bare qualified] [['(+ 2 3) '(k/+ 2 3)]
                              ['(or false true) '(k/or false true)]
                              ['(and true false) '(k/and true false)]
                              ['(mod 7 3) '(k/mod 7 3)]
                              ['(field object "name") '(k/field object "name")]]]
      (is (= (emitter/emit-expr qualified) (emitter/emit-expr bare)))
      (is (= (emitter/emit-expr qualified)
             (emitter/emit-expr (emitter/qualify-form context bare))))))
  (is (= 11 (a/value (fixture/arithmetic 3 4)))))

(deftest mutable-initializers-do-not-declare-their-operands
  (doseq [initializer '[undefined k/undefined]]
    (let [declaration (emitter/prepare-declaration
                       context {:kind :fn :name 'initialize :return :i32 :args []
                                :body [(list 'let ['x (list 'k/var initializer :i32)]
                                             '(k/= x 1) 'x)]})]
      (is (some #{'aguafria.keyword/undefined}
                (tree-seq coll? seq (:body declaration))))
      (is (not-any? #{'undefined} (tree-seq coll? seq (:body declaration))))))
  ;; A real argument with that spelling still has lexical precedence.
  (let [declaration (emitter/prepare-declaration
                     context {:kind :fn :name 'copy-local :return :i32
                              :args [{:name 'undefined :type :i32}]
                              :body '[(let [x (k/var undefined :i32)] x)]})]
    (is (not-any? #{'aguafria.keyword/undefined}
                  (tree-seq coll? seq (:body declaration))))))

(deftest referred-scopes-run-native-code
  (is (true? (a/value (fixture/boolean-or false true))))
  (is (false? (a/value (fixture/boolean-or false false))))
  (is (= 7 (a/value (fixture/captured-comptime)))))

(deftest syntax-helpers-use-the-original-clojure-macros
  (doseq [name '[let -> ->> as-> cond cond-> cond->> some-> some->> doto when when-not dotimes comment]]
    (let [helper (ns-resolve 'aguafria.keyword name)
          original (ns-resolve 'clojure.core name)]
      (is (:macro (meta helper)))
      (is (identical? @original @helper))
      (is (not (:aguafria/scoped? (meta helper))))))
  (with-redefs [jvm/invoke-scoped! (fn [& _] (throw (Exception. "let is not a native scope")))]
    (let [effects (atom [])]
      (is (= 42 (k/let [x (do (swap! effects conj :first) 20)
                        y (do (swap! effects conj :second) 22)]
                  (+ x y))))
      (is (= [:first :second] @effects))))
  (is (= 18 (a/value (fixture/threaded 4))))
  (is (= 18 (a/value (fixture/named-thread 4)))))

(deftest lexical-callables-are-resolved-as-bindings
  (doseq [op '[+ or field]]
    (binding [emitter/*keyword-context* context
              emitter/*lexical-bindings* #{op}
              emitter/*local-name-bindings* {op 'native_add}
              emitter/*local-type-bindings* {op false}]
      (is (= "native_add(1, 2)"
             (emitter/emit-expr (emitter/qualify-form context (list op 1 2)))))
      (is (thrown? Exception
                   (emitter/qualify-form context '(clojure.core/+ 1 2)))))))
