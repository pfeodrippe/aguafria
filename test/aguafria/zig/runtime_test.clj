(ns aguafria.zig.runtime-test
  (:require [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is testing]]))

(def ^:private function-declaration
  {:module "fixture.live"
   :kind :fn
   :name 'calculate
   :declaration-key [:fn 'calculate]
   :args [{:name 'value :type :i32 :properties {}}]
   :return :i32
   :body ['value]
   :export? true})

(def ^:private struct-declaration
  {:module "fixture.live"
   :kind :struct
   :name 'Point
   :declaration-key [:struct 'Point]
   :layout :extern
   :fields [{:name :x :type :i32 :properties {:doc "Horizontal"}}
            {:name :y :type :i32 :properties {}}]})

(deftest logical-identity-and-callable-abi-fingerprint-test
  (let [baseline (runtime/declaration-info function-declaration)
        body-change (runtime/declaration-info
                     (assoc function-declaration :body '[(+ value 1)]))
        argument-rename (runtime/declaration-info
                         (assoc-in function-declaration [:args 0 :name] 'input))
        signature-change (runtime/declaration-info
                          (assoc function-declaration :return :i64))]
    (is (= ["fixture.live" :fn "calculate"] (:logical-id baseline)))
    (is (= [:fn 'calculate] (:logical-key baseline)))
    (is (= 64 (count (:abi-fingerprint baseline))))
    (testing "implementation and parameter-name edits preserve the ABI"
      (is (= (:abi-fingerprint baseline) (:abi-fingerprint body-change)))
      (is (= (:abi-fingerprint baseline) (:abi-fingerprint argument-rename)))
      (is (not= (:implementation-fingerprint baseline)
                (:implementation-fingerprint body-change))))
    (testing "a signature edit creates a distinct ABI version key"
      (is (not= (:abi-fingerprint baseline)
                (:abi-fingerprint signature-change))))))

(deftest struct-schema-fingerprint-test
  (let [baseline (runtime/declaration-info struct-declaration)
        documentation-change
        (runtime/declaration-info
         (assoc-in struct-declaration [:fields 0 :properties :doc] "Renamed docs"))
        field-type-change
        (runtime/declaration-info
         (assoc-in struct-declaration [:fields 0 :type] :i64))
        field-order-change
        (runtime/declaration-info
         (update struct-declaration :fields #(vec (reverse %))))]
    (is (= ["fixture.live" :struct "Point"] (:logical-id baseline)))
    (is (= 64 (count (:schema-fingerprint baseline))))
    (testing "documentation does not affect memory layout identity"
      (is (= (:schema-fingerprint baseline)
             (:schema-fingerprint documentation-change))))
    (testing "field type and order are schema-breaking"
      (is (not= (:schema-fingerprint baseline)
                (:schema-fingerprint field-type-change)))
      (is (not= (:schema-fingerprint baseline)
                (:schema-fingerprint field-order-change))))))

(deftest converted-container-schema-ignores-method-bodies-test
  (let [declaration
        {:module "fixture.live"
         :kind :const
         :name 'Options
         :declaration-key [:const 'Options]
         :value
         '(aguafria.zig/container
           {:kind :struct :layout :normal}
           (aguafria.zig/field-decl count :u32)
           (aguafria.zig/fn-decl calculate :- :u32 [] 1))}
        baseline (runtime/declaration-info declaration)
        body-change
        (runtime/declaration-info
         (assoc declaration :value
                '(aguafria.zig/container
                  {:kind :struct :layout :normal}
                  (aguafria.zig/field-decl count :u32)
                  (aguafria.zig/fn-decl calculate :- :u32 [] 2))))
        layout-change
        (runtime/declaration-info
         (assoc declaration :value
                '(aguafria.zig/container
                  {:kind :struct :layout :packed}
                  (aguafria.zig/field-decl count :u32)
                  (aguafria.zig/fn-decl calculate :- :u32 [] 1))))]
    (is (= 64 (count (:schema-fingerprint baseline))))
    (is (= (:schema-fingerprint baseline)
           (:schema-fingerprint body-change)))
    (is (not= (:schema-fingerprint baseline)
              (:schema-fingerprint layout-change)))))

(deftest comptime-type-factory-schema-test
  (let [declaration
        {:module "fixture.live"
         :kind :fn
         :name 'OptionsType
         :qualified-name 'fixture.live/OptionsType
         :declaration-key [:fn 'OptionsType]
         :args []
         :return :type
         :body
         '[(aguafria.zig/container
            {:kind :struct :layout :normal}
            (aguafria.zig/field-decl count :u32)
            (aguafria.zig/fn-decl calculate :- :u32 [] 1))]}
        baseline (runtime/declaration-info declaration)
        method-change
        (runtime/declaration-info
         (assoc declaration :body
                '[(aguafria.zig/container
                   {:kind :struct :layout :normal}
                   (aguafria.zig/field-decl count :u32)
                   (aguafria.zig/fn-decl calculate :- :u32 [] 2))]))
        field-change
        (runtime/declaration-info
         (assoc declaration :body
                '[(aguafria.zig/container
                   {:kind :struct :layout :normal}
                   (aguafria.zig/field-decl count :u64)
                   (aguafria.zig/fn-decl calculate :- :u32 [] 1))]))]
    (is (:type-factory? baseline))
    (is (= 64 (count (:schema-fingerprint baseline))))
    (is (= (:schema-fingerprint baseline)
           (:schema-fingerprint method-change)))
    (is (not= (:schema-fingerprint baseline)
              (:schema-fingerprint field-change)))))
