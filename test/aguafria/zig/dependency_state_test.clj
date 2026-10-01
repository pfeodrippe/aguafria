(ns aguafria.zig.dependency-state-test
  (:require [aguafria.keyword]
            [aguafria.zig :as az]
            [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is testing]]))

(defn- scratch-ns [prefix]
  (let [n (create-ns (symbol (str "aguafria." prefix "-" (random-uuid))))]
    (binding [*ns* n]
      (refer 'clojure.core)
      (alias 'az 'aguafria.zig)
      (alias 'k 'aguafria.keyword))
    n))

(deftest ^:integration caller-owned-dependency-state-survives-inspection-and-reload
  (doseq [async? [false true]]
    (testing (str "async compilation: " async?)
      (let [config (az/configuration)
            owner (scratch-ns "state-owner")
            callers [(scratch-ns "state-caller") (scratch-ns "state-caller")]
            define! (fn [n form]
                      (binding [*ns* n] (eval form))
                      (az/await! (ns-name n)))
            call! (fn [n] (az/value ((ns-resolve n 'step!))))]
        (try
          (az/configure! {:async? async? :reloadable? true})
          (binding [*ns* owner runtime/*source-only-registration?* true]
            (eval '(az/defvar counter :u32 0))
            (eval '(az/defn increment! :u32 [] (k/+= counter 1) counter)))
          (doseq [caller callers]
            (binding [*ns* caller] (alias 'owner (ns-name owner))))
          (define! (first callers) '(az/defn step! :u32 [] (owner/increment!)))
          (is (= 1 (call! (first callers))))
          (let [counter (ns-resolve owner 'counter)
                before (first (filter :active? (az/state-versions counter)))
                address (:address before)]
            (is (pos-int? address))
            (is (= (str (ns-name (first callers)))
                   (get-in before [:storage-owner :module])))
            (is (= 1 (az/value @counter)) "Inspection must not install a fresh initializer")
            (define! (second callers) '(az/defn step! :u32 [] (owner/increment!)))
            (is (= 2 (call! (second callers))))
            (is (= 3 (call! (first callers))))
            (is (= 3 (az/value @counter)))
            (define! owner '(az/defn increment! :u32 [] (k/+= counter 2) counter))
            (is (= 5 (call! (first callers))))
            (is (= 7 (call! (second callers))))
            (dotimes [_ 2]
              (define! (first callers)
                '(az/defn step! :u32 [] (k/+ (owner/increment!) 10)))
              (is (= 19 (call! (first callers))))
              (define! owner '(az/defn reset-counter! :void [] (k/= counter 7)))
              ((ns-resolve owner 'reset-counter!)))
            (is (= 7 (az/value @counter)))
            (#'runtime/retire-module-quiescent-generations!
             (str (ns-name (first callers))))
            (is (some #(= (get-in before [:storage-owner :generation]) (:generation %))
                      (:native-generations (az/module-info (ns-name (first callers)))))
                "The caller library must stay loaded while it owns dependency storage")
            (is (= address (:address (first (filter :active? (az/state-versions counter)))))))
          (finally
            (az/configure! config)
            (doseq [n (conj callers owner)] (remove-ns (ns-name n)))))))))

(deftest ^:integration native-guard-does-not-shadow-user-declarations
  (let [n (scratch-ns "guard-names")]
    (try
      (binding [*ns* n]
        (eval '(az/defstruct Context [[:value :i32]]))
        (eval '(az/defconst Result :i32 2))
        (eval '(az/defconst context :i32 3))
        (eval '(az/defconst arguments :i32 4))
        (eval '(az/defconst function :i32 5))
        (eval '(az/defconst address :i32 6))
        (eval '(az/defn answer :i32 []
                 (let [input (Context {:value 22})]
                   (k/+ (:value input) Result context arguments function address)))))
      (is (= 42 (az/value ((ns-resolve n 'answer)))))
      (finally (remove-ns (ns-name n))))))
