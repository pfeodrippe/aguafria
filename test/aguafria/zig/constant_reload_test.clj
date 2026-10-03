(ns aguafria.zig.constant-reload-test
  (:require [aguafria.zig :as a]
            [aguafria.zig.runtime :as runtime]
            [aguafria.keyword]
            [clojure.test :refer [deftest is testing]]))

(defn- check-foreign-state-reload! [async?]
  ;; Mirrors the Studio's C-import -> type alias -> persistent audio-state
  ;; declarations, without opening an audio device or touching a live REPL.
  (let [old-config (a/configuration)
        provider (create-ns (symbol (str "aguafria.foreign-provider-" (random-uuid))))
        consumer (create-ns (symbol (str "aguafria.foreign-consumer-" (random-uuid))))
        load-provider!
        #(binding [*ns* provider]
           (eval '(a/defconst c-api (ak/cImport (ak/cInclude "stdlib.h"))))
           (eval '(a/defconst div_t (a/field c-api div_t))))
        load-consumer!
        #(binding [*ns* consumer]
           (eval '(a/defvar samples [:array 2 p/div_t]
                    [{:quot 17 :rem 2} {:quot 23 :rem 3}]))
           (eval '(a/defn first-sample :i32 []
                    (a/field (a/index samples 0) quot)))
           (eval '(a/defn change-sample! :void []
                    (set! (a/field (a/index samples 0) quot) 41))))]
    (try
      (a/configure! {:async? async? :reloadable? true})
      (doseq [n [provider consumer]]
        (binding [*ns* n]
          (refer 'clojure.core)
          (alias 'a 'aguafria.zig)
          (alias 'ak 'aguafria.keyword)))
      (binding [*ns* consumer]
        (alias 'p (ns-name provider)))
      (load-provider!)
      (load-consumer!)
      (a/await! (ns-name consumer))
      (let [samples (ns-resolve consumer 'samples)
            read-sample (ns-resolve consumer 'first-sample)
            before (first (filter :active? (a/state-versions samples)))]
        (is (pos-int? (:address before)))
        (is (string? (:schema-fingerprint before)))
        (is (= 17 (read-sample)))
        ((ns-resolve consumer 'change-sample!))
        (dotimes [_ 2]
          (load-provider!)
          (load-consumer!)
          (a/await! (ns-name consumer))
          (let [after (first (filter :active? (a/state-versions samples)))]
            (is (= 41 (read-sample)))
            (is (= (:address before) (:address after)))
            (is (= (:schema-fingerprint before) (:schema-fingerprint after))))))
      (finally
        (a/configure! old-config)
        (remove-ns (ns-name consumer))
        (remove-ns (ns-name provider))))))

(deftest unchanged-foreign-type-reload-preserves-state-test
  (doseq [async? [false true]]
    (testing (str "async compilation: " async?)
      (check-foreign-state-reload! async?))))

(deftest scalar-constant-invalidates-embedded-native-values-test
  (doseq [async? [false true]]
    (testing (str "async compilation: " async?)
      (let [old-config (a/configuration)
            provider (create-ns (symbol (str "aguafria.const-provider-" (random-uuid))))
            consumer (create-ns (symbol (str "aguafria.const-consumer-" (random-uuid))))]
        (try
          (a/configure! {:async? async? :reloadable? true})
          (doseq [n [provider consumer]]
            (binding [*ns* n] (refer 'clojure.core) (alias 'a 'aguafria.zig)))
          (binding [*ns* provider]
            (eval '(a/defconst base :i32 2))
            (eval '(a/defconst derived :i32 (+ base 1)))
            (eval '(a/defconst deeper :i32 (+ derived 1)))
            (eval '(a/defconst deepest :i32 (+ deeper 1)))
            (eval '(a/defn direct :i32 [] base))
            (eval '(a/defn computed :i32 [] deepest)))
          (binding [*ns* consumer]
            (alias 'p (ns-name provider))
            (eval '(a/defn indirect :i32 [] (p/computed)))
            (eval '(a/defn embedded :i32 [] (+ p/base 10))))
          (a/await! (ns-name provider))
          (a/await! (ns-name consumer))
          (let [direct (ns-resolve provider 'direct)
                computed (ns-resolve provider 'computed)
                indirect (ns-resolve consumer 'indirect)
                embedded (ns-resolve consumer 'embedded)]
            (is (= [2 5 5 12] [(direct) (computed) (indirect) (embedded)]))
            ;; Only reevaluate the constant: all these already-bound function
            ;; Vars must observe the new compiled value, without require :reload.
            (binding [*ns* provider] (eval '(a/defconst base :i32 7)))
            (a/await! (ns-name provider))
            (a/await! (ns-name consumer))
            (is (= 7 (direct)))
            (is (= 10 (computed)))
            (is (= 10 (indirect)))
            (is (= 17 (embedded))))
          (finally
            (a/configure! old-config)
            (remove-ns (ns-name consumer))
            (remove-ns (ns-name provider))))))))

(deftest failed-comptime-edit-is-retained-until-constants-match-test
  (doseq [async? [false true]
          assertion-first? [true false]]
    (testing (str "async compilation: " async?
                  ", assertion first: " assertion-first?)
      (let [old-config (a/configuration)
            context (create-ns (symbol (str "aguafria.pending-array-" (random-uuid))))
            module (ns-name context)
            consumer (create-ns (symbol (str "aguafria.array-consumer-" (random-uuid))))
            assertion-edit
            '(a/defcomptime concatenated-array
               (debug/assert
                (mem/eql :i32 (k/& all-of-it)
                         (k/& (a/array [1 2 3 4 5 6 7 8 10]
                                            :i32)))))
            constant-edit
            '(a/defconst part-two
               (a/array [5 6 7 8 10] :i32))
            [first-edit second-edit]
            (if assertion-first?
              [assertion-edit constant-edit]
              [constant-edit assertion-edit])
            evaluate! #(binding [*ns* context] (eval %))]
        (try
          (a/configure! {:async? async? :reloadable? true})
          (binding [*ns* context]
            (refer 'clojure.core)
            (require '[aguafria.zig :as a]
                     '[aguafria.keyword :as k]
                     '[aguafria.std.debug :as debug]
                     '[aguafria.std.mem :as mem]))
          (doseq [form
                  '[(a/defconst part-one
                      (a/array [1 2 3 4] :i32))
                    (a/defconst part-two
                      (a/array [5 6 7 8] :i32))
                    (a/defconst all-of-it (k/++ part-one part-two))
                    (a/defcomptime concatenated-array
                      (debug/assert
                       (mem/eql :i32 (k/& all-of-it)
                                (k/& (a/array [1 2 3 4 5 6 7 8]
                                                   :i32)))))
                    (a/defn array-length :usize [] (a/field all-of-it :len))
                    (a/defn last-item :i32 []
                      (a/index all-of-it (k/- (a/field all-of-it :len) 1)))]]
            (evaluate! form))
          (a/await! module)
          (binding [*ns* consumer]
            (refer 'clojure.core)
            (alias 'a 'aguafria.zig)
            (alias 'p module)
            (eval '(a/defn embedded-length :usize [] (a/field p/all-of-it :len))))
          (a/await! (ns-name consumer))
          (let [length (ns-resolve context 'array-length)
                last-item (ns-resolve context 'last-item)
                embedded-length (ns-resolve consumer 'embedded-length)
                _ (is (= [8 8] [(length) (last-item)]))
                _ (is (= 8 (embedded-length)))
                published (:published-generation (a/module-info module))
                failure (try
                          (evaluate! first-edit)
                          (a/await! module)
                          nil
                          (catch Throwable error error))]
            (is (= [8 8] [(length) (last-item)]))
            (is (some? failure) "The inconsistent intermediate edit must fail")
            (is (= :zig-compile (:aguafria/phase (runtime/error-data failure))))
            (is (= 8 (embedded-length)))
            (is (= published (:published-generation (a/module-info module))))
            (is (some #(= first-edit (:clojure-form %))
                      (:definitions (a/module-info module))))
            ;; Neither reevaluate the failed form nor reload the namespace.
            (evaluate! second-edit)
            (a/await! module)
            (a/await! (ns-name consumer))
            (is (= [9 10] [(length) (last-item)]))
            (is (= 9 (embedded-length)))
            (is (empty? (:pending-declaration-keys (a/module-info module))))
            (is (nil? (:error (a/module-info module)))))
          (finally
            (a/configure! old-config)
            (remove-ns (ns-name consumer))
            (remove-ns module)))))))
