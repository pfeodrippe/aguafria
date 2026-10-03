(ns aguafria.zig.retained-root-test
  (:require [aguafria.zig :as az]
            [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is]]))

(deftest first-jvm-call-retains-foreign-types-used-by-published-state
  (let [suffix (str (random-uuid))
        provider-name (symbol (str "aguafria.retained-provider-" suffix))
        consumer-name (symbol (str "aguafria.retained-consumer-" suffix))
        provider (create-ns provider-name)
        consumer (create-ns consumer-name)
        configuration (az/configuration)]
    (try
      (az/configure! {:async? false})
      (doseq [context [provider consumer]]
        (binding [*ns* context]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)))
      (binding [*ns* provider runtime/*source-only-registration?* true]
        (eval '(az/defstruct Box [[:value :i32]])))
      (binding [*ns* consumer runtime/*source-only-registration?* true]
        (alias 'provider provider-name)
        (eval '(az/defvar held provider/Box (provider/Box {:value 7})))
        (eval '(az/defn answer :i32 [] 42))
        (eval '(az/defn read-state :i32 [] (:value held))))
      ;; Publish the whole native module before asking for a JVM trampoline
      ;; whose own body does not reference Box or held.
      (runtime/recompile! consumer-name)
      (runtime/await! consumer-name)
      (is (some? (:published-generation (runtime/module-info consumer-name))))
      ;; The consumer retains its published type, even if the provider's next
      ;; definition has a different layout before the first JVM call.
      (binding [*ns* provider]
        (eval '(az/defstruct Box [[:other :i64]])))
      (with-open [result ((ns-resolve consumer 'answer))]
        (is (= 42 (az/value result))))
      (with-open [result ((ns-resolve consumer 'read-state))]
        (is (= 7 (az/value result))))
      (finally
        (az/configure! configuration)
        (remove-ns consumer-name)
        (remove-ns provider-name)))))
