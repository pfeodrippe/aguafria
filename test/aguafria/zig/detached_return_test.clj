(ns aguafria.zig.detached-return-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def context (the-ns 'aguafria.zig.detached-return-test))

(defn- normalize [form]
  (emitter/scoped-invocation-expression context (emitter/qualify-form context form)))

(defn- plan
  ([form] (plan form [] {:parameters [] :expression-arguments []} nil))
  ([form entries inputs retained]
   (#'jvm/scoped-plan (ns-name context) form entries inputs true nil retained)))

(deftest token-contract-and-syntax-capture
  (is (true? (:macro (meta #'k/return))))
  (is (true? (:aguafria/scoped? (meta #'k/return))))
  (is (= :native-syntax (:aguafria/evaluation (meta #'k/return))))
  (is (= '([] [expression]) (:arglists (meta #'k/return))))
  (binding [*ns* context]
    (doseq [source ['(k/return item) '(aguafria.keyword/return item)]]
      (let [expanded (apply @#'k/return source '{item nil unused nil} (rest source))]
        (is (= (list 'quote source) (nth expanded 2)))
        (is (= '(clojure.core/hash-map (quote item) item) (nth expanded 3)))
        (is (true? (last expanded)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"zero or one"
                          (@#'k/return '(k/return 1 2) {} 1 2))))
  (let [foreign (create-ns (gensym "detached.foreign"))]
    (try
      (intern foreign 'return (fn [x] [:foreign x]))
      (binding [*ns* foreign]
        (is (= [:foreign 3] (eval '(return 3)))))
      (finally (remove-ns (ns-name foreign)))))
  (is (= [:local 4] (let [return (fn [x] [:local x])] (return 4)))))

(deftest authored-provenance-survives-requalification
  (doseq [source ['(k/return) '(k/return 17) '(aguafria.keyword/return item)]]
    (binding [emitter/*lexical-bindings* '#{item}]
      (let [once (emitter/qualify-form context source)
            twice (emitter/qualify-form context once)]
        (is (= 'aguafria.keyword/return (:aguafria/scoped-token (meta once))))
        (is (= source (:aguafria/scoped-template (meta twice))))
        (is (= (:aguafria/scoped-captures (meta once))
               (:aguafria/scoped-captures (meta twice)))))))
  (let [referred (create-ns (gensym "detached.referred"))]
    (try
      (binding [*ns* referred] (refer 'aguafria.keyword :only '[return]))
      (let [qualified (emitter/qualify-form referred '(return 19))]
        (is (= 'aguafria.keyword/return (:aguafria/scoped-token (meta qualified))))
        (is (= 19 (emitter/scoped-invocation-expression referred qualified))))
      (finally (remove-ns (ns-name referred)))))
  (binding [emitter/*local-type-bindings* {'return false}]
    (let [qualified (emitter/qualify-form context '(return 7))]
      (is (nil? (:aguafria/scoped-token (meta qualified))))
      (is (= qualified (emitter/scoped-invocation-expression context qualified))))))

(deftest root-only-payload-and-void-planning
  (is (= 17 (normalize '(k/return 17))))
  (is (= '(aguafria.zig/block) (normalize '(k/return))))
  (is (= 17 (:expression (plan '(k/return 17)))))
  (let [result (plan '(k/return (a/error-value :SkipZigTest)))]
    (is (false? (:propagates-errors? result)))
    (is (nil? (:declaration result)))
    (is (not= 'return (first (:expression result))))
    (is (str/includes? (emitter/emit-expr context (:expression result)) "error.SkipZigTest")))
  (is (emitter/scoped-result-context-required? context '(k/return (k/intCast 7))))
  (is (not (emitter/scoped-result-context-required? context '(k/return (a/error-value :SkipZigTest))))))

(deftest captures-guards-and-writebacks-follow-the-lowered-payload
  (let [entries [{:name 'x :type :u32}]
        inputs {:parameters [{:name 'input :type :u32}] :expression-arguments ['input]}
        ordinary (plan '(k/return x) entries inputs nil)
        retained (plan '(k/return x) entries inputs {'x {:native-source "7"}})]
    (is (= 'input (:expression ordinary)))
    (is (= 1 (count (:result-preconditions retained))))
    (is (= "7" (emitter/emit-expr context (:expression retained)))))
  (doseq [comptime? [false true]]
    (let [result (plan '(k/return (a/block (k/+= x 1) x))
                       [{:name 'x :type :u32 :mutable? true :comptime? comptime?}]
                       {:parameters [{:name 'input :type (if comptime? :u32 :usize)}]
                        :expression-arguments ['input]} nil)]
      (is (= comptime? (:mutation-result? result)))
      (is (= (if comptime? ['x] []) (:mutation-captures result)))
      (is (not-any? #(and (seq? %) (= 'return (first %)))
                    (tree-seq coll? seq (:expression result)))))))

(deftest external-exits-fail-before-publication-or-native-activity
  (let [calls (atom [])
        fail! (fn [& args] (swap! calls conj args) (throw (ex-info "Unexpected activity" {})))]
    (with-redefs-fn
      {#'jvm/validate-scoped-declaration! fail!
       #'jvm/publish-expression-plan! fail!
       #'runtime/invoke! fail!
       #'runtime/invoke-with-result! fail!}
      #(doseq [form ['(k/return (k/return 3))
                    '(k/return (a/block (k/return 3) 4))
                    '(a/block (k/return 3))
                    '(k/switch 1 0 (k/return 3) 4)
                    '(k/return (k/break :outside 3))
                    '(k/return (k/continue :outside))
                    '(k/return (a/raw "return 42"))
                    '(k/return (a/raw "opaque()"))
                    '(a/block (a/raw "return 42;"))]]
         (let [failure (try
                         (#'jvm/prepare-scoped-plan! (ns-name context) form []
                                                    {:parameters [] :expression-arguments []}
                                                    true nil)
                         nil
                         (catch clojure.lang.ExceptionInfo error error))]
           (is (= :unsupported-scoped-exit (:reason (ex-data failure))) (pr-str form)))))
    (is (empty? @calls)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"zero or one"
                        (normalize '(k/return 1 2))))
  ;; Compiler-generated canonical returns have no authored invocation contract.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"external exit"
                        (normalize '(return 1)))))

(deftest nested-declarations-keep-their-own-returns
  (doseq [owned ['(fn-decl owned :u32 [] (return 7))
                 '(container {} [(fn-decl owned :u32 [] (return 7))])]]
    ;; Already-qualified syntax: safety analysis does not traverse declarations.
    (let [root (with-meta (list 'return owned)
                 {:aguafria/scoped-token 'aguafria.keyword/return})]
      (is (= owned (emitter/scoped-invocation-expression context root))))))

(deftest terminal-observation-precedes-native-return-without-a-trailing-break
  (doseq [source ['(k/return) '(k/return (a/error-value :SkipZigTest))]]
    (let [qualified (emitter/qualify-form context source)
          observations (atom [])
          ordinary (emitter/emit-stmt-in context qualified)
          inspected (binding [emitter/*expression-observer*
                              (fn [observation]
                                (swap! observations conj observation)
                                (:source observation))]
                      (emitter/emit-stmt-in context qualified))
          returns (filter #(= "return" (get-in % [:var-meta :aguafria/token :zig-token])) @observations)
          observation (first returns)
          probe ((:place-probe observation) "comptime { @compileLog(\"before\"); }" "ignored")]
      (is (= ordinary inspected))
      (is (= 1 (count returns)))
      (is (:terminal? observation))
      (is (= source (:aguafria/scoped-template (meta (:form observation)))))
      (is (< (.indexOf probe "@compileLog") (.indexOf probe "return")))
      (is (not (str/includes? probe "break :")))))
  (let [observations (atom [])]
    (binding [emitter/*expression-observer* (fn [observation]
                                           (swap! observations conj observation)
                                           (:source observation))]
      (emitter/emit-stmt-in context '(return 9)))
    (is (empty? @observations))))
