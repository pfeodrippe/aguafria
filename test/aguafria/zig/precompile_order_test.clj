(ns aguafria.zig.precompile-order-test
  (:require [aguafria.zig :as a]
            [aguafria.std.lang]
            [aguafria.zig.explain :as explain]
            [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is testing]]))

(deftest prepared-inferred-error-results-use-the-native-storage-type
  (let [context (create-ns (symbol (str "aguafria.precompile-error-result-" (random-uuid))))
        module (str (ns-name context))
        events (atom [])]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (eval '(a/defn success :!u32 [] 42))
        (eval '(a/defn empty-success :!void [])))
      (doseq [name '[success empty-success]]
        (is (= :prepared (:status (runtime/precompile-function! (symbol module (str name)))))))
      (is (empty? (:native-generations (runtime/module-info module))))
      (binding [explain/*reporter* #(swap! events conj %)]
        (doseq [[name expected] [['success {:ok 42}] ['empty-success {:ok nil}]]]
          (let [result ((ns-resolve context name))]
            (try
              (is (= expected (a/value result)))
              (finally (a/close! result))))))
      (is (empty? (filter #(= :compiled (:event %)) @events)) (pr-str @events))
      (finally (remove-ns (ns-name context))))))

(deftest prepared-imported-results-include-their-value-reader
  (let [context (create-ns (symbol (str "aguafria.precompile-imported-result-" (random-uuid))))
        module (str (ns-name context))
        events (atom [])]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (alias 'builtin 'aguafria.std.lang)
        (eval '(a/defn location builtin/SourceLocation []
                 (a/init {:module "example" :file "example.clj" :fn_name "location" :line 42 :column 3}
                          builtin/SourceLocation))))
      (is (= :prepared (:status (runtime/precompile-function! (symbol module "location")))))
      (is (empty? (:native-generations (runtime/module-info module))))
      (swap! (var-get #'runtime/registry) update module
             dissoc :jvm-callable-declaration-keys :jvm-value-declaration-keys
             :jvm-type-declaration-keys)
      (binding [explain/*reporter* #(swap! events conj %)]
        (let [result ((ns-resolve context 'location))]
          (try
            (is (= {:line 42 :column 3} (select-keys (a/value result) [:line :column])))
            (finally (a/close! result)))))
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
            (alias 'a 'aguafria.zig)
            (eval '(a/defn helper :i32 [[x :i32]] (+ x 1)))
            (eval '(a/defn caller :i32 [[x :i32]] (helper x))))
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
            (is (= 42 (a/value ((ns-resolve context 'caller) 41))))
            (is (= 42 (a/value ((ns-resolve context 'helper) 41)))))
          (is (empty? (filter #(and (= module (:module %))
                                   (= :compiled (:event %))) @events)))
          (is (= 2 (count (filter #(and (= module (:module %))
                                       (= :disk-cache-hit (:event %))) @events))))
          (binding [*ns* context]
            (eval '(a/defn helper :i32 [[x :i32]] (+ x 2))))
          (runtime/await! module)
          (is (= 43 (a/value ((ns-resolve context 'caller) 41))))
          (is (= 43 (a/value ((ns-resolve context 'helper) 41))))
          (finally
            (remove-ns (ns-name context))))))))
