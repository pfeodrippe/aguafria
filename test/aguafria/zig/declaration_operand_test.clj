(ns aguafria.zig.declaration-operand-test
  (:require [aguafria.zig.discovery :as discovery]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.value :as value]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest boxed-declared-constants-retain-compiler-value-observations
  (let [context (create-ns (gensym "operand-test-"))]
    (try
      ;; A native defconst may expose a boxed JVM scalar, not a ZigValue.
      (intern context (with-meta 'constant {:aguafria/declaration {:kind :const}}) 1234)
      (intern context (with-meta 'variable {:aguafria/declaration {:kind :var}}) 0)
      (binding [emitter/*keyword-context* context
                emitter/*lexical-bindings* #{}]
        (with-redefs [value/value (fn [& _] (throw (ex-info "Must not realize values" {})))]
          (let [constant (#'discovery/declaration-argument-schema str 'constant "native_schema")
                variable (#'discovery/declaration-argument-schema str 'variable "native_schema")
                tuple (#'discovery/operand-schema str "tuple_expression" nil
                                                  ['constant ['constant]] "argumentSchema")
                literal-tuple (#'discovery/operand-schema pr-str "tuple_expression"
                                                          {:properties {:jvm/literal? true}} ["hi" 'constant] "argumentSchema")]
            (is (str/includes? constant "{:representations ["))
            (is (str/includes? constant "comptimeValue(constant)"))
            (is (str/includes? constant "native_schema"))
            (is (= "native_schema" variable))
            ;; A declared format string passed to a comptime parameter must
            ;; keep the compiler's value schema, not become a borrowed pointer.
            (is (= "comptime_schema"
                   (#'discovery/declaration-argument-schema str 'constant "comptime_schema" true)))
            (is (str/includes? tuple "comptimeValue(constant)"))
            (is (= 2 (count (re-seq #"\{:tuple \[" tuple))))
            (is (not (str/includes? literal-tuple "jvmMapStringArgumentSchema")))
            (is (str/includes? literal-tuple "comptimeArgument"))))
        (binding [emitter/*lexical-bindings* #{'constant}]
          (is (= "local_schema"
                 (#'discovery/declaration-argument-schema str 'constant "local_schema")))))
      (finally (remove-ns (ns-name context))))))

(deftest literal-tuple-elements-do-not-expand-runtime-alternatives
  (let [boolean-schema (#'discovery/operand-schema str "true"
                                                   {:properties {:jvm/literal? true}} true "argumentSchema")
        runtime-schema (#'discovery/operand-schema str "true" nil true "argumentSchema")]
    (is (= "__aguafria_probe.comptimeValue(true)" boolean-schema))
    (is (str/includes? runtime-schema "schema(@TypeOf(true))"))))
