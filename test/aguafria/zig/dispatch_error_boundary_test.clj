(ns aguafria.zig.dispatch-error-boundary-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.discovery :as discovery]
            [clojure.test :refer [deftest is]]))

(a/defn reload-failure [:error-union :anyerror :void] []
  (k/return (a/error-value :BeforeReload)))

(a/defn generic-result :!i32 [[input :anytype]] input)
(a/defn inferred-failure :!void [] (a/error-value :InferredFailure))

(deftest discovery-keeps-the-concrete-bridge-error-union-representation
  (let [context (the-ns 'aguafria.zig.dispatch-error-boundary-test)
        source (#'discovery/jvm-value-type-source context '(generic-result 1))]
    (is (nil? source) "Generic encoded calls must not be widened like concrete ABI calls"))
  (let [context (the-ns 'aguafria.zig.dispatch-error-boundary-test)
        source (#'discovery/jvm-value-type-source context '(inferred-failure))]
    (is (= [:error-union :anyerror :void] (:root-type source)))
    (is (:retain-native-observation? source))))

(a/defstruct FailureBox [[:result [:error-union :anyerror :i32]]])
(a/defn make-failure-box FailureBox []
  (FailureBox {:result (a/error-value :BoxFailure)}))
(a/defn inspect-error-input :i32 [[input [:error-union :anyerror :i32]]]
  (a/catch-capture [err] input
                   (if (k/== err (a/error-value :InputFailure)) 17 29)))

(deftest error-bearing-input-is-interpreted-in-the-caller-image
  (with-open [result (k/switch (k/as 1 :i32)
                       1 (inspect-error-input
                          (k/as (a/error-value :InputFailure) [:error-union :anyerror :i32]))
                       (k/as 0 :i32))]
    (is (= 17 (a/value result)))))

(deftest nominal-aggregate-errors-remain-local
  (is (= :BoxFailure
         (try
           (k/switch (k/as 1 :i32)
             1 (k/try (:result (make-failure-box)))
             (k/as 0 :i32))
           (catch clojure.lang.ExceptionInfo error
             (:error-name (ex-data error)))))))

(defn call-error-scope []
  (try
    (k/switch (k/as 1 :i32)
      1 (k/try (reload-failure))
      (k/unreachable))
    (catch clojure.lang.ExceptionInfo error
      (:error-name (ex-data error)))))

(deftest raw-dispatch-requires-error-free-inputs-and-results
  (let [dispatch? #'runtime/dispatchable-declaration?
        declaration {:kind :fn :return :i32 :args [{:name 'x :type :i32}]}]
    (is (dispatch? declaration))
    (doseq [type [:!void [:error-union :anyerror :i32]
                  [:error-set [:Oops]] 'SomeAlias
                  [:optional [:error-union :anyerror :i32]]
                  [:array 2 [:error-set [:Oops]]]
                  [:* [:error-union :anyerror :i32]]
                  [:slice 'UnknownRecord]
                  [:fn :!void []]]]
      (is (not (dispatch? (assoc declaration :return type))) (pr-str type))
      (is (not (dispatch? (assoc declaration :args [{:name 'x :type type}]))) (pr-str type)))
    (is (dispatch? (assoc declaration :args [{:name 'x :type [:* :i32]}])))
    (is (dispatch? (assoc declaration :return [:array 4 :u8])))))

(deftest local-error-calls-still-update-through-dependent-callers
  (try
    (is (= :BeforeReload (call-error-scope)))
    (binding [*ns* (the-ns 'aguafria.zig.dispatch-error-boundary-test)]
      (eval '(a/defn reload-failure [:error-union :anyerror :void] []
               (k/return (a/error-value :AfterReload)))))
    (is (= :AfterReload (call-error-scope)))
    (finally
      (binding [*ns* (the-ns 'aguafria.zig.dispatch-error-boundary-test)]
        (eval '(a/defn reload-failure [:error-union :anyerror :void] []
                 (k/return (a/error-value :BeforeReload))))))))
