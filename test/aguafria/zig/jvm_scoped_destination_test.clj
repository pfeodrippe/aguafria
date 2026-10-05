(ns aguafria.zig.jvm-scoped-destination-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.emitter :as emitter]
            [clojure.test :refer [deftest is]]))

(defn- emitted-contexts [return-type body]
  (let [context (the-ns 'aguafria.zig.jvm-scoped-destination-test)
        declaration (emitter/prepare-declaration
                     context {:kind :fn :name 'fixture :return return-type
                              :args [{:name 'input :type :u32}]
                              :body [body] :implicit-return? true})
        observations (atom [])
        source (emitter/emit-module (str (ns-name context)) [declaration])
        observed-source
        (binding [emitter/*expression-observer*
                  (fn [observation]
                    (swap! observations conj
                           (select-keys observation [:form :result-context :result-context-origin]))
                    (:source observation))]
          (emitter/emit-module (str (ns-name context)) [declaration]))]
    {:source source :observed-source observed-source :observations @observations}))

(deftest native-result-arms-carry-the-real-destination-without-changing-source
  (let [result (emitted-contexts
                :u32 '(a/switch input (case [0] (k/intFromFloat 42.0)) (case-else 7)))
        scopes (filter #(= 'switch (some-> % :form first)) (:observations result))]
    (is (= (:source result) (:observed-source result)))
    (is (seq scopes))
    (is (every? #(= :u32 (:result-context %)) scopes))
    (is (every? #(= :return-destination (:result-context-origin %)) scopes)))
  (let [result (emitted-contexts
                :u64 '(aguafria.zig/raw "consume(switch (input) { 0 => 1, else => 2 })"))]
    ;; An enclosing return is not an operand's destination contract.
    (is (= (:source result) (:observed-source result)))))

(deftest peer-result-arms-use-the-original-native-envelope
  (let [parent '(a/if-capture {:payload [value] :error [err]} input
                              (k/+ value 3)
                              (k/switch err (case [(a/error-value :Rejected)] nil)))
        result (emitted-contexts :void (list 'let ['choice parent] '(k/= :_ choice)))
        inner (filter #(= 'switch (some-> % :form first)) (:observations result))]
    (is (= (:source result) (:observed-source result)))
    (is (seq inner))
    (is (every? #(= :peer-envelope (:result-context-origin %)) inner))
    (is (every? #(= 'aguafria.keyword/TypeOf (first (:result-context %))) inner))
    (is (every? #(= 'if-capture (first (second (:result-context %)))) inner)))
  (let [parent '(a/catch-capture [err] input
                                 (k/switch err (case [(a/error-value :Rejected)] nil)))
        result (emitted-contexts :void (list 'let ['choice parent] '(k/= :_ choice)))
        inner (filter #(= 'switch (some-> % :form first)) (:observations result))]
    (is (seq inner))
    (is (every? #(= :peer-envelope (:result-context-origin %)) inner))
    (is (every? #(= 'catch-capture (first (second (:result-context %)))) inner))))

(deftest external-exits-and-ordinary-operands-do-not-acquire-a-return-contract
  (let [exit '(a/switch input (case [0] (k/return 7)) (case-else 2))
        result (emitted-contexts :u32 exit)
        scopes (filter #(= 'switch (some-> % :form first)) (:observations result))]
    (is (seq scopes))
    (is (every? #(nil? (:result-context-origin %)) scopes)))
  (let [result (emitted-contexts
                :u64 '(k/+ (a/switch input (case [0] 1) (case-else 2)) 3))
        scopes (filter #(= 'switch (some-> % :form first)) (:observations result))]
    (is (seq scopes))
    (is (every? #(nil? (:result-context-origin %)) scopes))))
