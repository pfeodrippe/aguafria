(ns learn.jvm-audit-test
  (:require [clojure.test :refer [deftest is]]
            [learn.jvm-audit :as audit]))

(deftest inventory-keeps-lexical-setup-and-does-not-invent-arguments
  (let [{:keys [cases]}
        (audit/plan
         "(ns fixture.audit)
          (az/defconst a 1)
          (az/defn- add :i32 [[x :i32]] (+ x a))
          (az/deftest check
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
                       (az/defn main :void [] (let [x] (k/= x 1)))
                       (az/deftest branching (if condition (danger!) (safe!)))"))]
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
                       (az/defn main :void []
                         (let [n (-> 1 (k/as :i32) k/var)] n))"))]
    (is (some #(and (= '(-> 1 (k/as :i32) k/var) (:expression %))
                    (= :requires-control-context (:status %))) cases))
    (is (not-any? #(= '(k/as :i32) (:expression %)) cases))))
