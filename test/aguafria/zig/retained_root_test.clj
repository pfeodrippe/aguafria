(ns aguafria.zig.retained-root-test
  (:require [aguafria.zig :as a]
            [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is]]))

(deftest first-jvm-call-retains-foreign-types-used-by-published-state
  (let [suffix (str (random-uuid))
        provider-name (symbol (str "aguafria.retained-provider-" suffix))
        consumer-name (symbol (str "aguafria.retained-consumer-" suffix))
        provider (create-ns provider-name)
        consumer (create-ns consumer-name)
        configuration (a/configuration)]
    (try
      (a/configure! {:async? false})
      (doseq [context [provider consumer]]
        (binding [*ns* context]
          (refer 'clojure.core)
          (alias 'a 'aguafria.zig)))
      (binding [*ns* provider runtime/*source-only-registration?* true]
        (eval '(a/defstruct Box [[:value :i32]])))
      (binding [*ns* consumer runtime/*source-only-registration?* true]
        (alias 'provider provider-name)
        (eval '(a/defvar held provider/Box (provider/Box {:value 7})))
        (eval '(a/defn answer :i32 [] 42))
        (eval '(a/defn read-state :i32 [] (:value held))))
      ;; Publish the whole native module before asking for a JVM trampoline
      ;; whose own body does not reference Box or held.
      (runtime/recompile! consumer-name)
      (runtime/await! consumer-name)
      (is (some? (:published-generation (runtime/module-info consumer-name))))
      ;; The consumer retains its published type, even if the provider's next
      ;; definition has a different layout before the first JVM call.
      (binding [*ns* provider]
        (eval '(a/defstruct Box [[:other :i64]])))
      (with-open [result ((ns-resolve consumer 'answer))]
        (is (= 42 (a/value result))))
      (with-open [result ((ns-resolve consumer 'read-state))]
        (is (= 7 (a/value result))))
      (finally
        (a/configure! configuration)
        (remove-ns consumer-name)
        (remove-ns provider-name)))))
