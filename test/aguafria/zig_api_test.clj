(ns aguafria.zig-api-test
  (:require [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is]]))

(deftest declaration-doc-attributes-and-inferred-types-test
  (let [namespace-symbol (gensym "aguafria.zig-api-test.scratch-")
        scratch (create-ns namespace-symbol)
        declarations (atom [])]
    (try
      (binding [*ns* scratch
                runtime/*registration-batch* declarations]
        (refer 'clojure.core)
        (require '[aguafria.zig :as az])
        (eval '(az/defconst clean-constant
                 "Inspectable constant."
                 {:export false :public false :source-comment false}
                 42))
        (eval '(az/defvar clean-variable {:public false} :u32 1))
        (eval '(az/defn clean-function :u32
                 "Inspectable function."
                 {:export false :public true} [[x :u32]]
                 (+ x clean-variable)))
        (eval '(az/defstruct CleanPoint
                 "Inspectable struct."
                 {:public false}
                 [[:x :f32] [:y {:doc "Vertical"} :f32]])))
      (let [by-name (into {} (map (juxt :name identity)) @declarations)]
        (is (= 4 (count @declarations)))
        (is (nil? (:type (get by-name 'clean-constant))))
        (is (= {:export false :public false :source-comment false}
               (:attributes (get by-name 'clean-constant))))
        (is (= :u32 (:type (get by-name 'clean-variable))))
        (is (= :normal (:layout (get by-name 'CleanPoint))))
        (is (= "Inspectable constant."
               (:doc (meta (ns-resolve scratch 'clean-constant)))))
        (is (= 42 (var-get (ns-resolve scratch 'clean-constant))))
        (is (= "Inspectable function."
               (:doc (meta (ns-resolve scratch 'clean-function)))))
        (is (= "Inspectable struct."
               (:doc (meta (ns-resolve scratch 'CleanPoint))))))
      (finally
        (remove-ns namespace-symbol)))))

(deftest canonical-functions-and-named-test-vars
  (let [namespace-symbol (gensym "aguafria.zig-api-test.canonical-")
        scratch (create-ns namespace-symbol)
        declarations (atom [])]
    (try
      (binding [*ns* scratch runtime/*registration-batch* declarations]
        (refer 'clojure.core)
        (require '[aguafria.zig :as az])
        (require '[aguafria.keyword :as ak])
        (eval '(az/defn answer :i32
                 "Canonical return-type-first function."
                 {:attrs #{:explicit-return}}
                 []
                 (ak/return 42)))
        (eval '(az/defn- private-answer :i32 [] 42))
        (let [first-var (eval '(az/deftest answer-test "A named test Var."))
              second-var (eval '(az/deftest answer-test "A named test Var."))]
          (is (var? first-var))
          (is (identical? first-var second-var))
          (is (= 'answer-test (:name (meta first-var))))
          (is (= "A named test Var." (:doc (meta first-var))))
          (is (:aguafria/test (meta first-var))))
        (doseq [form '[(az/defn old :- :i32 [] 1)
                      (az/defn old {:attrs #{}} :- :i32 [] 1)
                      (az/defn old {:attrs #{}} :i32 [] 1)
                      (az/deftest "string name")
                      (az/deftest nil)
                      (az/deftest {:attrs #{}} old-test)
                      (az/deftest old-test {:zig/test-name "old label"})
                      (az/deftest old-test {:zig/test-name another-name})
                      (az/deftest old-test {:zig/test-name nil})
                      (az/deftest ^{:zig/test-name "old label"} old-test)]]
          (is (thrown? Exception (eval form)) (pr-str form))))
      (let [by-name (into {} (map (juxt :name identity)) @declarations)]
        (is (= :i32 (:return (by-name 'answer))))
        (is (false? (:implicit-return? (by-name 'answer))))
        (is (true? (:implicit-return? (by-name 'private-answer))))
        (is (false? (:public? (by-name 'private-answer))))
        (is (= "answer-test" (:test-name (by-name 'answer-test))))
        (is (= [] (:body (by-name 'answer-test)))))
      (finally (remove-ns namespace-symbol)))))
