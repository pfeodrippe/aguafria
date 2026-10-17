(ns aguafria.zig.detached-return-native-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- fixture! []
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.detached-return-fixture :reload)))

(deftest error-set-is-a-value-and-host-execution-continues
  (let [result (k/return (a/error-value :SkipZigTest))
        decoded (a/value result)]
    (is (value/zig-error? decoded))
    (is (= "SkipZigTest" (name (:name decoded)))))
  (is (= :after ((fn [] (a/value (k/return 17)) :after))))
  (is (= 17 (a/value (k/return 17))))
  (let [result (k/return)]
    (is (nil? (a/value result)))
    (is (= :void (value/qualified-type result))))
  (is (= 7 (a/value (k/as (k/return (k/intCast 7)) :u32)))))

(deftest payload-executes-once-with-live-storage-and-native-try
  (fixture!)
  (with-open [counter (k/var 0 :u32)]
    (let [form '(k/return (aguafria.zig.detached-return-fixture/bump (k/& counter)))
          result (jvm/invoke-scoped! 'aguafria.zig.detached-return-native-test
                                     form {'counter counter} true)]
      (is (= 1 (a/value result)))
      (is (= 1 (a/value result)))
      (is (= 1 (a/value counter)))))
  (is (= 11 (a/value (k/return (k/try (aguafria.zig.detached-return-fixture/maybe-value false))))))
  (let [failure (try
                  (a/value (k/return (k/try (aguafria.zig.detached-return-fixture/maybe-value true))))
                  nil
                  (catch clojure.lang.ExceptionInfo error error))]
    (is (= :native-error (:aguafria/phase (ex-data failure))))
    (is (= "Rejected" (name (:error-name (ex-data failure)))))))

(deftest preparation-and-type-query-never-execute-payload
  (fixture!)
  (with-open [counter (k/var 0 :u32)]
    ;; @TypeOf is native compiler analysis, never an application invocation.
    (a/value (k/TypeOf (aguafria.zig.detached-return-fixture/bump (k/& counter))))
    (is (= 0 (a/value counter))))
  (let [fail! (fn [& _] (throw (ex-info "Preparation invoked application code" {})))]
    (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
      (is (some? (jvm/precompile-scoped!
                  {:caller 'aguafria.zig.detached-return-fixture
                   :form '(k/return (a/error-value :SkipZigTest))
                   :captures [] :types [] :result? true}))))))

(deftest terminal-probes-compile-in-the-original-return-destination
  (fixture!)
  (let [module 'aguafria.zig.detached-return-fixture
        seen (atom [])
        result (runtime/inspect-module!
                module
                (fn [declarations]
                  {:source
                   (binding [emitter/*expression-observer*
                             (fn [{:keys [source place-probe var-meta] :as observation}]
                               (if (= "return" (get-in var-meta [:aguafria/token :zig-token]))
                                 (do (swap! seen conj observation)
                                     {:source (place-probe "comptime { _ = @as(u32, 1); }" "unused")})
                                 source))]
                     (str (emitter/emit-module (str module) declarations)
                          "\ntest { _ = &terminal_value; _ = &bump; _ = &maybe_value; }\n"))}))]
    (is (seq @seen))
    (is (= 0 (:exit result)) (:err result))
    (is (every? #(and (:terminal? %) (:aguafria/scoped-template (meta (:form %)))) @seen))))
