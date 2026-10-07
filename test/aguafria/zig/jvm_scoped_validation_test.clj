(ns aguafria.zig.jvm-scoped-validation-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is]]))

(deftest scoped-validation-specializes-dependent-return-types
  (let [context (create-ns (symbol (str "aguafria.scoped-validation-" (random-uuid))))
        module (ns-name context)
        _ (binding [*ns* context runtime/*source-only-registration?* true]
            (refer 'clojure.core)
            (alias 'a 'aguafria.zig)
            (alias 'k 'aguafria.keyword)
            (eval '(a/defconst root :u8 0)))
        before (runtime/registered-declarations module)
        base {:kind :fn :name 'candidate
              :qualified-name (symbol (str module) "candidate")
              :declaration-key [:fn 'candidate]
              :zig-prefix "inline"
              :args [{:name 'input :type :i32}]}
        candidates
        [(assoc base :return :void :body ['(aguafria.keyword/= :_ input)])
         (assoc base :return :i32 :body ['(return input)])
         (assoc base :return [:error-union :anyerror :void]
                :body ['(aguafria.keyword/= :_ input)])
         (assoc base :return [:error-union :anyerror
                              '(aguafria.keyword/TypeOf (aguafria.keyword/+ input 1))]
                :body ['(return (aguafria.keyword/+ input 1))])]]
    (try
      (doseq [candidate candidates]
        (let [proof (#'jvm/validate-scoped-declaration! context candidate)]
          (is (= :zig-compiler (:basis proof)))
          (is (zero? (:exit proof)))
          (is (= before (runtime/registered-declarations module)))))
      (let [candidate (assoc (last candidates) :body ['(return false)])
            failure (try (#'jvm/validate-scoped-declaration! context candidate)
                         nil
                         (catch clojure.lang.ExceptionInfo error error))]
        (is (:unpublished? (ex-data failure)))
        (is (re-find #"expected type.*i32" (:stderr (ex-data failure))))
        (is (= before (runtime/registered-declarations module))))
      (finally
        (swap! @#'runtime/registry dissoc (str module))
        (remove-ns module)))))

(deftest scoped-validation-resolves-unpublished-support-functions
  (let [context (create-ns (symbol (str "aguafria.scoped-support-" (random-uuid))))
        module (ns-name context)
        _ (binding [*ns* context runtime/*source-only-registration?* true]
            (refer 'clojure.core)
            (alias 'a 'aguafria.zig)
            (eval '(a/defconst root :u8 0)))
        before (runtime/registered-declarations module)
        support {:kind :fn :name 'unpublished-support
                 :qualified-name (symbol (str module) "unpublished-support")
                 :declaration-key [:fn 'unpublished-support]
                 :args [{:name 'input :type :i32}]
                 :return :i32 :body ['input]}
        candidate {:kind :fn :name 'candidate
                   :qualified-name (symbol (str module) "candidate")
                   :declaration-key [:fn 'candidate]
                   :args [{:name 'input :type :i32}]
                   :return :i32 :body ['(unpublished-support input)]}]
    (try
      (let [proof (#'jvm/validate-scoped-declaration!
                   context candidate {:support-declarations [support]})]
        (is (= :zig-compiler (:basis proof)))
        (is (zero? (:exit proof)))
        (is (= before (runtime/registered-declarations module))))
      (let [failure (try
                      (#'jvm/validate-scoped-declaration!
                       context (assoc candidate :body ['(missing-support input)])
                       {:support-declarations [support]})
                      nil
                      (catch clojure.lang.ExceptionInfo error error))]
        (is (re-find #"Unresolved Zig reference" (ex-message failure)))
        (is (= before (runtime/registered-declarations module))))
      (finally
        (swap! @#'runtime/registry dissoc (str module))
        (remove-ns module)))))
