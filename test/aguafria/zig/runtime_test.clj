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

(deftest callable-abi-tracks-signature-types-not-body-local-types-test
  (let [logical-id ["fixture.types" :struct "Payload"]
        dependency (fn [schema]
                     [[logical-id schema nil (str schema "-shape")]])
        body-v1
        (runtime/declaration-info
         (assoc function-declaration
                :type-dependency-fingerprints (dependency "payload-v1")
                :abi-type-dependency-fingerprints []))
        body-v2
        (runtime/declaration-info
         (assoc function-declaration
                :type-dependency-fingerprints (dependency "payload-v2")
                :abi-type-dependency-fingerprints []))
        signature-v1
        (runtime/declaration-info
         (assoc function-declaration
                :type-dependency-fingerprints (dependency "payload-v1")
                :abi-type-dependency-fingerprints (dependency "payload-v1")))
        signature-v2
        (runtime/declaration-info
         (assoc function-declaration
                :type-dependency-fingerprints (dependency "payload-v2")
                :abi-type-dependency-fingerprints (dependency "payload-v2")))]
    (testing "a body-local struct edit recompiles without breaking dispatch"
      (is (= (:abi-fingerprint body-v1) (:abi-fingerprint body-v2)))
      (is (not= (:implementation-fingerprint body-v1)
                (:implementation-fingerprint body-v2))))
    (testing "a struct layout reachable from the signature versions the ABI"
      (is (not= (:abi-fingerprint signature-v1)
                (:abi-fingerprint signature-v2))))))

(deftest source-fingerprint-covers-emission-without-churning-type-identity-test
  (let [reference
        (fn [alias]
          (with-meta 'dependency/value
            {:aguafria/zig-reference
             {:kind :const
              :module "fixture.dependency"
              :zig-name "value"
              :import-name "fixture.dependency"
              :import-alias alias
              :logical-id ["fixture.dependency" :const "value"]}}))
        baseline (runtime/declaration-info
                  (assoc function-declaration :body [(reference "dep")]))
        documentation-change
        (runtime/declaration-info
         (assoc function-declaration
                :doc "New generated Zig documentation"
                :body [(reference "dep")]))
        reference-change
        (runtime/declaration-info
         (assoc function-declaration :body [(reference "dependency")]))]
    (is (= 64 (count (:source-fingerprint baseline))))
    (is (= (:source-fingerprint baseline)
           (:source-fingerprint (runtime/declaration-info baseline))))
    (is (= (:abi-fingerprint baseline)
           (:abi-fingerprint documentation-change)
           (:abi-fingerprint reference-change)))
    (is (not= (:source-fingerprint baseline)
              (:source-fingerprint documentation-change)))
    (is (not= (:source-fingerprint baseline)
              (:source-fingerprint reference-change)))))

(deftest local-emission-metadata-invalidates-native-source-test
  (let [local (fn [metadata] (with-meta 'local metadata))
        declaration
        (fn [metadata]
          (runtime/declaration-info
           (assoc function-declaration
                  :body [(list 'let [(local metadata) 1] 'local)])))
        inferred (declaration {})
        mutable (declaration {:var true})
        typed (declaration {:zig/type :i32})]
    (testing "const/var and local type edits retain the callable ABI"
      (is (= (:abi-fingerprint inferred)
             (:abi-fingerprint mutable)
             (:abi-fingerprint typed))))
    (testing "metadata read by the emitter changes implementation/source identity"
      (is (not= (:implementation-fingerprint inferred)
                (:implementation-fingerprint mutable)))
      (is (not= (:implementation-fingerprint inferred)
                (:implementation-fingerprint typed)))
      (is (not= (:source-fingerprint inferred)
                (:source-fingerprint mutable)))
      (is (not= (:source-fingerprint inferred)
                (:source-fingerprint typed))))))

(deftest bounded-module-source-cache-reuses-and-invalidates-plans-test
  (let [cache (var-get #'aguafria.zig.runtime/module-source-cache)
        empty-cache (var-get #'aguafria.zig.runtime/empty-module-source-cache)
        module-sources (var-get #'aguafria.zig.runtime/module-sources)
        baseline (runtime/declaration-info function-declaration)
        changed (runtime/declaration-info
                 (assoc function-declaration :body '[(+ value 1)]))]
    (reset! cache empty-cache)
    (try
      (let [first-plan (module-sources "fixture.live" [baseline])
            repeated-plan (module-sources "fixture.live" [baseline])
            current-implementation (apply str (repeat 64 "f"))
            identity-only-plan
            (module-sources
             "fixture.live"
             [(assoc baseline
                     :implementation-fingerprint current-implementation)])
            changed-plan (module-sources "fixture.live" [changed])
            getter-plan (module-sources "fixture.live" [changed]
                                        #{(:declaration-key changed)})
            cache-stats (:module-source-cache (runtime/stats))]
        (is (= first-plan repeated-plan))
        (is (= current-implementation
               (get-in identity-only-plan
                       [:reload-source-dispatch-specs
                        (:declaration-key baseline)
                        :implementation-fingerprint])))
        (is (not= (:source first-plan) (:source changed-plan)))
        (is (not= (:compile-source changed-plan)
                  (:compile-source getter-plan)))
        (is (= 2 (:hit-count cache-stats)))
        (is (= 3 (:miss-count cache-stats)))
        (is (= 3 (:entry-count cache-stats)))
        (is (<= (:entry-count cache-stats) (:entry-limit cache-stats)))
        (is (<= (:weight-chars cache-stats)
                (:weight-limit-chars cache-stats))))
      (finally
        (reset! cache empty-cache)))))

(deftest module-source-cache-evicts-oldest-rendered-plan-test
  (let [cache (var-get #'aguafria.zig.runtime/module-source-cache)
        empty-cache (var-get #'aguafria.zig.runtime/empty-module-source-cache)
        module-sources (var-get #'aguafria.zig.runtime/module-sources)]
    (reset! cache empty-cache)
    (try
      (with-redefs-fn
        {#'aguafria.zig.runtime/module-source-cache-entry-limit 2}
        (fn []
          (doseq [increment [1 2 3]]
            (module-sources
             "fixture.live"
             [(runtime/declaration-info
               (assoc function-declaration
                      :body `[(+ value ~increment)]))]))))
      (let [cache-stats (:module-source-cache (runtime/stats))]
        (is (= 2 (:entry-count cache-stats)))
        (is (= 1 (:eviction-count cache-stats))))
      (finally
        (reset! cache empty-cache)))))

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
    (is (not= (:implementation-fingerprint baseline)
              (:implementation-fingerprint method-change)))
    (is (not= (:schema-fingerprint baseline)
              (:schema-fingerprint field-change)))))

(deftest referenced-type-shapes-are-stable-and-layout-sensitive-test
  (let [logical-id ["fixture.live" :struct "Node"]
        node-reference
        (fn [schema]
          (with-meta 'Node
            {:aguafria/zig-reference
             {:kind :struct
              :module "fixture.live"
              :logical-id logical-id
              :schema-fingerprint schema
              :shape-fingerprint "node-shape"}}))
        node
        (fn [schema]
          (runtime/declaration-info
           {:module "fixture.live"
            :kind :struct
            :name 'Node
            :declaration-key [:struct 'Node]
            :layout :extern
            :type-dependency-fingerprints
            [[logical-id schema nil "node-shape"]]
            :fields [{:name :next
                      :type [:optional [:* (node-reference schema)]]
                      :properties {}}]}))
        dependency-id ["fixture.types" :struct "Payload"]
        wrapper
        (fn [shape]
          (runtime/declaration-info
           {:module "fixture.live"
            :kind :struct
            :name 'Wrapper
            :declaration-key [:struct 'Wrapper]
            :layout :extern
            :type-dependency-fingerprints
            [[dependency-id "published-schema" nil shape]]
            :fields [{:name :payload
                      :type (with-meta 'fixture.types/Payload
                              {:aguafria/zig-reference
                               {:kind :struct
                                :module "fixture.types"
                                :logical-id dependency-id
                                :schema-fingerprint "published-schema"
                                :shape-fingerprint shape}})
                      :properties {}}]}))]
    (testing "a self reference does not recursively churn its own schema"
      (is (= (:schema-fingerprint (node "generation-one"))
             (:schema-fingerprint (node "generation-two")))))
    (testing "a direct dependency layout change still versions the owner"
      (is (not= (:schema-fingerprint (wrapper "payload-v1"))
                (:schema-fingerprint (wrapper "payload-v2")))))))
