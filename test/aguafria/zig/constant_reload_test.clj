(ns aguafria.zig.constant-reload-test
  (:require [aguafria.zig :as az]
            [clojure.test :refer [deftest is testing]]))

(deftest scalar-constant-invalidates-embedded-native-values-test
  (doseq [async? [false true]]
    (testing (str "async compilation: " async?)
      (let [old-config (az/configuration)
            provider (create-ns (symbol (str "aguafria.const-provider-" (random-uuid))))
            consumer (create-ns (symbol (str "aguafria.const-consumer-" (random-uuid))))]
        (try
          (az/configure! {:async? async? :reloadable? true})
          (doseq [n [provider consumer]]
            (binding [*ns* n] (refer 'clojure.core) (alias 'az 'aguafria.zig)))
          (binding [*ns* provider]
            (eval '(az/defconst base :i32 2))
            (eval '(az/defconst derived :i32 (+ base 1)))
            (eval '(az/defconst deeper :i32 (+ derived 1)))
            (eval '(az/defconst deepest :i32 (+ deeper 1)))
            (eval '(az/defn direct :- :i32 [] base))
            (eval '(az/defn computed :- :i32 [] deepest)))
          (binding [*ns* consumer]
            (alias 'p (ns-name provider))
            (eval '(az/defn indirect :- :i32 [] (p/computed)))
            (eval '(az/defn embedded :- :i32 [] (+ p/base 10))))
          (az/await!)
          (let [direct (ns-resolve provider 'direct)
                computed (ns-resolve provider 'computed)
                indirect (ns-resolve consumer 'indirect)
                embedded (ns-resolve consumer 'embedded)]
            (is (= [2 5 5 12] [(direct) (computed) (indirect) (embedded)]))
            ;; Only reevaluate the constant: all these already-bound function
            ;; Vars must observe the new compiled value, without require :reload.
            (binding [*ns* provider] (eval '(az/defconst base :i32 7)))
            (az/await!)
            (is (= 7 (direct)))
            (is (= 10 (computed)))
            (is (= 10 (indirect)))
            (is (= 17 (embedded))))
          (finally
            (az/configure! old-config)
            (remove-ns (ns-name consumer))
            (remove-ns (ns-name provider))))))))
