(ns aguafria.zig.constant-reload-test
  (:require [aguafria.zig :as az]
            [aguafria.keyword]
            [clojure.test :refer [deftest is testing]]))

(defn- check-foreign-state-reload! [async?]
  ;; Mirrors the Studio's C-import -> type alias -> persistent audio-state
  ;; declarations, without opening an audio device or touching a live REPL.
  (let [old-config (az/configuration)
        provider (create-ns (symbol (str "aguafria.foreign-provider-" (random-uuid))))
        consumer (create-ns (symbol (str "aguafria.foreign-consumer-" (random-uuid))))
        load-provider!
        #(binding [*ns* provider]
           (eval '(az/defconst c-api (ak/cImport (ak/cInclude "stdlib.h"))))
           (eval '(az/defconst div_t (az/field c-api div_t))))
        load-consumer!
        #(binding [*ns* consumer]
           (eval '(az/defvar samples [:array 2 p/div_t]
                    [{:quot 17 :rem 2} {:quot 23 :rem 3}]))
           (eval '(az/defn first-sample :- :i32 []
                    (az/field (az/index samples 0) quot)))
           (eval '(az/defn change-sample! :- :void []
                    (set! (az/field (az/index samples 0) quot) 41))))]
    (try
      (az/configure! {:async? async? :reloadable? true})
      (doseq [n [provider consumer]]
        (binding [*ns* n]
          (refer 'clojure.core)
          (alias 'az 'aguafria.zig)
          (alias 'ak 'aguafria.keyword)))
      (binding [*ns* consumer]
        (alias 'p (ns-name provider)))
      (load-provider!)
      (load-consumer!)
      (az/await! (ns-name consumer))
      (let [samples (ns-resolve consumer 'samples)
            read-sample (ns-resolve consumer 'first-sample)
            before (first (filter :active? (az/state-versions samples)))]
        (is (pos-int? (:address before)))
        (is (string? (:schema-fingerprint before)))
        (is (= 17 (read-sample)))
        ((ns-resolve consumer 'change-sample!))
        (dotimes [_ 2]
          (load-provider!)
          (load-consumer!)
          (az/await! (ns-name consumer))
          (let [after (first (filter :active? (az/state-versions samples)))]
            (is (= 41 (read-sample)))
            (is (= (:address before) (:address after)))
            (is (= (:schema-fingerprint before) (:schema-fingerprint after))))))
      (finally
        (az/configure! old-config)
        (remove-ns (ns-name consumer))
        (remove-ns (ns-name provider))))))

(deftest unchanged-foreign-type-reload-preserves-state-test
  (doseq [async? [false true]]
    (testing (str "async compilation: " async?)
      (check-foreign-state-reload! async?))))

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
