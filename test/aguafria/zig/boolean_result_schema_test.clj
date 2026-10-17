(ns aguafria.zig.boolean-result-schema-test
  "Keep type-slot observations distinct from finite boolean argument domains."
  (:require [aguafria.zig.discovery :as discovery]
            [aguafria.zig.runtime :as runtime]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- observation [schema-kind parameter]
  ;; Conversion result slots pass nil source form and request schema. The
  ;; expression is emitted Zig, never evaluated by this test.
  (#'discovery/operand-schema pr-str "__result" parameter nil schema-kind))

(deftest type-slots-do-not-expand-the-bool-value-domain
  (let [source (observation "schema" nil)]
    (is (not (str/includes? source "__aguafria_probe.argumentSchema(bool)")))
    (is (str/includes? source "__aguafria_probe.schema(@TypeOf(__result))"))
    ;; Preserve native type-valued/comptime result handling; this is not a
    ;; replacement of all observation types with their runtime carriers.
    (is (str/includes? source "{:comptime-type "))
    (is (str/includes? source "__aguafria_probe.literal(__result)"))))

(deftest compiler-observes-boolean-construction-targets-as-types-not-values
  (binding [runtime/*compile-only?* true runtime/*source-only-registration?* true]
    (require 'aguafria.zig.boolean-result-schema-fixture))
  (let [report (discovery/analyze! 'aguafria.zig.boolean-result-schema-fixture)
        conversions (filter :conversion? (:operations report))]
    (is (= :zig-compiler (:basis report)))
    (is (zero? (get-in report [:baseline :exit])))
    (is (= 2 (count conversions)))
    (is (= #{[:bool {:comptime true}] [:bool {:comptime false}]}
           (set (mapcat :signatures conversions))))))

(deftest ordinary-bool-arguments-retain-their-native-domain
  (is (str/includes? (observation "argumentSchema" nil)
                     "__aguafria_probe.argumentSchema(bool)")))

(deftest explicit-constant-argument-contracts-are-not-domain-expanded
  (doseq [parameter [{:properties {:zig/prefix "comptime"}}
                     {:properties {:jvm/literal? true}}]]
    (is (not (str/includes? (observation "argumentSchema" parameter)
                            "__aguafria_probe.argumentSchema(bool)")))))
