(ns aguafria.zig.dependency-state-test
  (:require [aguafria.keyword]
            [aguafria.zig :as a]
            [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is testing]]))

(defn- scratch-ns [prefix]
  (let [n (create-ns (symbol (str "aguafria." prefix "-" (random-uuid))))]
    (binding [*ns* n]
      (refer 'clojure.core)
      (alias 'a 'aguafria.zig)
      (alias 'k 'aguafria.keyword))
    n))

(deftest ^:integration caller-owned-dependency-state-survives-inspection-and-reload
  (doseq [async? [false true]]
    (testing (str "async compilation: " async?)
      (let [config (a/configuration)
            owner (scratch-ns "state-owner")
            callers [(scratch-ns "state-caller") (scratch-ns "state-caller")]
            define! (fn [n form]
                      (binding [*ns* n] (eval form))
                      (a/await! (ns-name n)))
            call! (fn [n] (a/value ((ns-resolve n 'step!))))]
        (try
          (a/configure! {:async? async? :reloadable? true})
          (binding [*ns* owner runtime/*source-only-registration?* true]
            (eval '(a/defvar counter :u32 0))
            (eval '(a/defn increment! :u32 [] (k/+= counter 1) counter)))
          (doseq [caller callers]
            (binding [*ns* caller] (alias 'owner (ns-name owner))))
          (define! (first callers) '(a/defn step! :u32 [] (owner/increment!)))
          (is (= 1 (call! (first callers))))
          (let [counter (ns-resolve owner 'counter)
                before (first (filter :active? (a/state-versions counter)))
                address (:address before)]
            (is (pos-int? address))
            (is (= (str (ns-name (first callers)))
                   (get-in before [:storage-owner :module])))
            (is (= 1 (a/value @counter)) "Inspection must not install a fresh initializer")
            (define! (second callers) '(a/defn step! :u32 [] (owner/increment!)))
            (is (= 2 (call! (second callers))))
            (is (= 3 (call! (first callers))))
            (is (= 3 (a/value @counter)))
            (define! owner '(a/defn increment! :u32 [] (k/+= counter 2) counter))
            (is (= 5 (call! (first callers))))
            (is (= 7 (call! (second callers))))
            (dotimes [_ 2]
              (define! (first callers)
                '(a/defn step! :u32 [] (k/+ (owner/increment!) 10)))
              (is (= 19 (call! (first callers))))
              (define! owner '(a/defn reset-counter! :void [] (k/= counter 7)))
              ((ns-resolve owner 'reset-counter!)))
            (is (= 7 (a/value @counter)))
            (#'runtime/retire-module-quiescent-generations!
             (str (ns-name (first callers))))
            (is (some #(= (get-in before [:storage-owner :generation]) (:generation %))
                      (:native-generations (a/module-info (ns-name (first callers)))))
                "The caller library must stay loaded while it owns dependency storage")
            (is (= address (:address (first (filter :active? (a/state-versions counter)))))))
          (finally
            (a/configure! config)
            (doseq [n (conj callers owner)] (remove-ns (ns-name n)))))))))

(deftest ^:integration native-guard-does-not-shadow-user-declarations
  (let [n (scratch-ns "guard-names")]
    (try
      (binding [*ns* n]
        (eval '(a/defstruct Context [[:value :i32]]))
        (eval '(a/defconst Result :i32 2))
        (eval '(a/defconst context :i32 3))
        (eval '(a/defconst arguments :i32 4))
        (eval '(a/defconst function :i32 5))
        (eval '(a/defconst address :i32 6))
        (eval '(a/defn answer :i32 []
                 (let [input (Context {:value 22})]
                   (k/+ (:value input) Result context arguments function address)))))
      (is (= 42 (a/value ((ns-resolve n 'answer)))))
      (finally (remove-ns (ns-name n))))))
