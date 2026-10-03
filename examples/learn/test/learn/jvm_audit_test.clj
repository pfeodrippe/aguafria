(ns learn.jvm-audit-test
  (:require [clojure.test :refer [deftest is]]
            [learn.jvm-audit :as audit]))

(deftest resource-bindings-are-not-detached-from-their-lifetime
  (let [cases (:cases (audit/plan
                       "(ns fixture.resources)
                       (comment (with-open [arena (open-arena)] (consume arena)))"))]
    (is (= 1 (count (filter #(= :comment-call (:kind %)) cases))))
    (is (some #(and (= '(with-open [arena (open-arena)] (consume arena))
                       (:expression %))
                    (= :comment-call (:kind %))) cases))
    (is (every? :status (filter #(= '[arena (open-arena)] (:expression %)) cases)))))

(deftest nested-comment-bodies-are-not-mislabeled-as-authored-calls
  (let [cases (:cases (audit/plan
                       "(ns fixture.comments)
                       (comment (let [x 1] (consume x) (consume x)))"))]
    (is (= 1 (count (filter #(= :comment-call (:kind %)) cases))))
    (is (= 2 (count (filter #(and (= :body-expression (:kind %))
                                  (= '(consume x) (:expression %))) cases))))))

(deftest inventory-keeps-lexical-setup-and-does-not-invent-arguments
  (let [{:keys [cases]}
        (audit/plan
         "(ns fixture.audit)
          (a/defconst a 1)
          (a/defn- add :i32 [[x :i32]] (+ x a))
          (a/deftest check
            (let [x (k/var 0 :i32) y (+ a 2)]
              (k/= x y)
              (debug/assert (k/== x 3))))")]
    (is (= #{'a 'add 'check}
           (set (map :declaration (filter #(= :var (:kind %)) cases)))))
    (is (= :requires-arguments (:status (first (filter #(= :function-body (:kind %)) cases)))))
    (is (some #(= '(let [x (k/var 0 :i32)] (+ a 2))
                  (let [f (:form %)] (if (and (seq? f) (= 'do (first f))) (second f) f)))
              cases))
    (is (some #(= '(do (let [x (k/var 0 :i32) y (+ a 2)]
                         (do (k/= x y) (k/== x 3))))
                  (:form %)) cases))
    (is (some #(= '(check) (:form %)) cases))))

(deftest intentionally-invalid-and-control-dependent-forms-remain-visible
  (let [cases (:cases (audit/plan
                       "(ns fixture.invalid)
                       (a/defn main :void [] (let [x] (k/= x 1)))
                       (a/deftest branching (if condition (danger!) (safe!)))"))]
    (is (some #(= '(let [x] (k/= x 1)) (:expression %)) cases))
    (is (some #(= :requires-control-context (:status %)) cases))
    (is (not-any? #(= '(danger!) (:form %)) cases))))

(deftest inventory-uses-bounded-virtual-workers-and-preserves-order
  (is (= (mapv vector (range 10) (repeat true))
         (audit/parallel-inventory 2
                                   #(vector % (.isVirtual (Thread/currentThread)))
                                   (range 10))))
  (is (thrown? clojure.lang.ExceptionInfo
               (audit/parallel-inventory 0 identity []))))

(deftest threaded-steps-are-not-evaluated-with-their-input-missing
  (let [cases (:cases (audit/plan
                       "(ns fixture.threaded)
                       (a/defn main :void []
                         (let [n (-> 1 (k/as :i32) k/var)] n))"))]
    (is (some #(and (= '(k/as 1 :i32) (:expression %)) (:form %)) cases))
    (is (some #(and (= '(k/var (k/as 1 :i32)) (:expression %)) (:form %)) cases))
    (is (not-any? #(and (= '(k/as :i32) (:expression %)) (:form %)) cases))))

(deftest thread-last-steps-keep-their-input
  (let [cases (:cases (audit/plan
                       "(ns fixture.threadlast)
                       (a/defn main :void []
                         (let [xs [1 2]] (->> xs (map inc) vec)))"))]
    (is (some #(= '(map inc xs) (:expression %)) cases))
    (is (some #(= '(vec (map inc xs)) (:expression %)) cases))
    (is (not-any? #(and (= '(map inc) (:expression %)) (:form %)) cases))))

(deftest dependent-descendants-are-inventoried-without-detaching-bindings
  (let [cases (:cases (audit/plan
                       "(ns fixture.context)
                       (a/defn f :i32 [[x :i32]] (k/+ x (k/* x 2)))
                       (a/deftest branching
                         (a/if-capture-stmt [value] optional
                           (debug/assert (k/== value 2))))"))]
    (is (some #(and (= '(k/* x 2) (:expression %))
                    (= :requires-arguments (:status %))) cases))
    (is (some #(and (= '(k/== value 2) (:expression %))
                    (= :requires-control-context (:status %))) cases))
    (is (not-any? #(and (= '(k/== value 2) (:expression %))
                        (:form %)) cases))))

(deftest sequential-forms-retain-their-own-prefix-and-native-block
  (let [cases (:cases (audit/plan
                       "(ns fixture.sequence)
                       (a/deftest check
                         (do (prepare!) (inspect!)))
                       (a/deftest scoped
                         (a/block (prepare!) (inspect!)))"))]
    (is (some #(= '(do (do (prepare!) (inspect!))) (:form %)) cases))
    (is (some #(= '(do (a/block (do (prepare!) (inspect!)))) (:form %)) cases))))

(deftest comment-entrypoints-and-container-descendants-are-visible
  (let [cases (:cases (audit/plan
                       "(ns fixture.entries)
                       (a/defstruct Point
                         [[:x {:default (k/+ 1 2)} :i32]
                          (a/fn get-x :i32 [[self Point]] (a/field self :x))])
                       (comment (require 'fixture.setup) (main))"))]
    (is (some #(and (= '(k/+ 1 2) (:expression %))
                    (= :requires-declaration-context (:status %))) cases))
    (is (some #(and (= :comment-call (:kind %))
                    (= '(do (require 'fixture.setup) (main)) (:form %))) cases))))

(deftest source-read-errors-preserve-the-readable-inventory
  (let [{:keys [namespace cases]} (audit/plan
                                   "(ns fixture.invalid-source)
                                  (a/defconst good 1)
                                  (a/defconst broken [")]
    (is (= 'fixture.invalid-source namespace))
    (is (some #(= 'good (:declaration %)) cases))
    (is (= :source-read-failed (:status (last cases))))))

(deftest map-value-expressions-are-inventoried
  (let [cases (:cases (audit/plan
                       "(ns fixture.map)
                       (a/defconst point (Point {:x (k/+ 1 2)}))"))]
    (is (some #(= '(k/+ 1 2) (:expression %)) cases))))

(deftest handlerless-try-retains-context-for-assertion-operands
  (let [cases (:cases (audit/plan
                       "(ns fixture.assertion)
                       (a/deftest check
                         (let [n (k/var 1 :i32)]
                           (k/+= n 1)
                           (try (testing/expectEqual 2 n))))"))]
    (is (some #(= '(do (let [n (k/var 1 :i32)]
                         (do (k/+= n 1) (try (do n))))) (:form %)) cases))
    (is (not-any? #(and (= :requires-control-context (:status %))
                        (= '(testing/expectEqual 2 n) (:expression %))) cases))))

(deftest callee-expressions-and-native-symbol-operands-are-inventoried
  (let [cases (:cases (audit/plan
                       "(ns fixture.callee)
                       (a/deftest check
                         (let [list (make-list)]
                           ((a/field list :append) testing/allocator 3)))"))]
    (is (some #(and (= '(a/field list :append) (:expression %)) (:form %)) cases))
    (is (some #(and (= 'testing/allocator (:expression %)) (:form %)) cases))))
