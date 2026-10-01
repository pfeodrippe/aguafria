(ns aguafria.zig.precompile-order-test
  (:require [aguafria.zig :as az]
            [aguafria.std.builtin]
            [aguafria.zig.explain :as explain]
            [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is testing]]))

(deftest prepared-imported-results-include-their-value-reader
  (let [context (create-ns (symbol (str "aguafria.precompile-imported-result-" (random-uuid))))
        module (str (ns-name context))
        events (atom [])]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'az 'aguafria.zig)
        (alias 'builtin 'aguafria.std.builtin)
        (eval '(az/defn location builtin/SourceLocation []
                 (az/init {:module "example" :file "example.clj" :fn_name "location" :line 42 :column 3}
                          builtin/SourceLocation))))
      (is (= :prepared (:status (runtime/precompile-function! (symbol module "location")))))
      (is (empty? (:native-generations (runtime/module-info module))))
      (swap! (var-get #'runtime/registry) update module
             dissoc :jvm-callable-declaration-keys :jvm-value-declaration-keys
             :jvm-type-declaration-keys)
      (binding [explain/*reporter* #(swap! events conj %)]
        (let [result ((ns-resolve context 'location))]
          (try
            (is (= {:line 42 :column 3} (select-keys (az/value result) [:line :column])))
            (finally (az/close! result)))))
      (is (empty? (filter #(= :compiled (:event %)) @events)) (pr-str @events))
      (is (seq (filter #(= :disk-cache-hit (:event %)) @events)))
      (finally (remove-ns (ns-name context))))))

(deftest exact-materialization-selects-only-its-own-jvm-wrapper
  (doseq [request-key [:jvm-callable-declaration-keys
                      :jvm-value-declaration-keys
                      :jvm-type-declaration-keys]]
    (with-redefs-fn
      {#'runtime/registry
       (atom {"owner" {request-key #{[:fn 'helper] [:fn 'caller]}}
              "dependency" {request-key #{[:fn 'caller]}}})}
      (fn []
        (is (= #{[:fn 'helper] [:fn 'caller]}
               (#'runtime/jvm-wrapper-requests "owner" request-key)))
        (binding [runtime/*materialize-declaration*
                  {:module "owner" :declaration-key [:fn 'caller]}]
          (is (= #{[:fn 'caller]}
                 (#'runtime/jvm-wrapper-requests "owner" request-key)))
          (is (empty? (#'runtime/jvm-wrapper-requests "dependency" request-key))))))))

(deftest callable-artifacts-do-not-depend-on-preparation-order
  (doseq [order '[[helper caller] [caller helper]]]
    (testing (str order)
      (let [context (create-ns (symbol (str "aguafria.precompile-order-" (random-uuid))))
            module (str (ns-name context))
            events (atom [])]
        (try
          (binding [*ns* context runtime/*source-only-registration?* true]
            (refer 'clojure.core)
            (alias 'az 'aguafria.zig)
            (eval '(az/defn helper :i32 [[x :i32]] (+ x 1)))
            (eval '(az/defn caller :i32 [[x :i32]] (helper x))))
          (doseq [name order]
            (is (= :prepared
                   (:status (runtime/precompile-function! (symbol module (str name)))))))
          ;; Preparation loads no native image. Remove only its wrapper-request
          ;; history to reproduce demand loading from a fresh registry.
          (is (empty? (:native-generations (runtime/module-info module))))
          (swap! (var-get #'runtime/registry) update module
                 dissoc :jvm-callable-declaration-keys :jvm-value-declaration-keys
                 :jvm-type-declaration-keys)
          (binding [explain/*reporter* #(swap! events conj %)]
            (is (= 42 (az/value ((ns-resolve context 'caller) 41))))
            (is (= 42 (az/value ((ns-resolve context 'helper) 41)))))
          (is (empty? (filter #(and (= module (:module %))
                                   (= :compiled (:event %))) @events)))
          (is (= 2 (count (filter #(and (= module (:module %))
                                       (= :disk-cache-hit (:event %))) @events))))
          (binding [*ns* context]
            (eval '(az/defn helper :i32 [[x :i32]] (+ x 2))))
          (runtime/await! module)
          (is (= 43 (az/value ((ns-resolve context 'caller) 41))))
          (is (= 43 (az/value ((ns-resolve context 'helper) 41))))
          (finally
            (remove-ns (ns-name context))))))))
