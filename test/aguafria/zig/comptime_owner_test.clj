(ns aguafria.zig.comptime-owner-test
  (:require [aguafria.zig.discovery :as discovery]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.test :refer [deftest is]]))

(deftest compiler-context-observation-does-not-force-comptime-code-into-runtime
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.comptime-owner-fixture))
  (let [report (with-redefs [value/realize! (fn [& _] (throw (Exception. "Preparation must not realize application values")))]
                 (discovery/analyze! 'aguafria.zig.comptime-owner-fixture))
        remainder (first (filter #(= 'aguafria.keyword/% (:function %)) (:operations report)))
        initializer (first (filter #(= 'folded (:declaration-name %)) (:operations report)))
        mixed (first (filter #(and (= 'mixed (:declaration-name %))
                                   (= 'aguafria.keyword/+= (:function %))) (:operations report)))]
    (is (zero? (get-in report [:baseline :exit])))
    (is (false? (:compiler-errors? report)))
    (is (= #{:comptime} (:observed-contexts remainder)))
    (is (= :execution-context (:observation-kind remainder)))
    (is (empty? (:signatures remainder)))
    (is (:declared-constant-initializer? initializer))
    (is (= #{:comptime} (:observed-contexts initializer)))
    (is (seq (:signatures initializer))
        "Observe the call's operands without making its comptime-only body runtime-valid")
    (is (contains? (:observed-contexts mixed) :runtime))
    (is (seq (:signatures mixed)))))
