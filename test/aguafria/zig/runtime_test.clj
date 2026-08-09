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

(deftest development-debug-information-configuration-test
  (let [old-config (runtime/configuration)]
    (try
      (testing "ephemeral development libraries are stripped by default"
        (is (= :none (:development-debug-info old-config)))
        (is (= :shared (:development-panic old-config)))
        (is (= ["-fstrip"]
               ((var-get #'aguafria.zig.runtime/development-compiler-arguments)
                old-config))))
      (testing "full native debug information remains an explicit option"
        (is (= :full
               (:development-debug-info
                (runtime/configure! {:development-debug-info :full}))))
        (is (= []
               ((var-get #'aguafria.zig.runtime/development-compiler-arguments)
                (runtime/configuration)))))
      (testing "invalid profiles fail before a build can be scheduled"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Unsupported development debug-information mode"
             (runtime/configure! {:development-debug-info :symbols-only}))))
      (testing "full per-generation panic machinery remains available"
        (is (= :full
               (:development-panic
                (runtime/configure! {:development-panic :full})))))
      (testing "invalid panic profiles fail before compilation"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Unsupported development panic profile"
             (runtime/configure! {:development-panic :fast-but-silent}))))
      (finally
        (runtime/configure! old-config)))))

(deftest zig-version-is-cached-by-compiler-identity-test
  (let [cache (var-get #'aguafria.zig.runtime/zig-version-cache)
        old-cache @cache
        calls (atom 0)]
    (reset! cache {})
    (try
      (with-redefs-fn
        {#'aguafria.zig.runtime/executable-identity
         (constantly [:fixture-zig 1])
         #'aguafria.zig.runtime/run-command
         (fn [command directory]
           (swap! calls inc)
           {:exit 0
            :out "0.16.0\n"
            :err ""
            :command command
            :directory directory})}
        (fn []
          (is (= "0.16.0"
                 ((var-get #'aguafria.zig.runtime/zig-version))))
          (is (= "0.16.0"
                 ((var-get #'aguafria.zig.runtime/zig-version))))))
      (is (= 1 @calls))
      (finally
        (reset! cache old-cache)))))

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

(deftest selective-reference-refresh-keeps-compatible-function-edits-local-test
  (let [refresh (var-get
                 #'aguafria.zig.runtime/refresh-live-declaration-references)
        original-info runtime/declaration-info
        calls (atom 0)
        target
        (original-info
         (dissoc (assoc function-declaration
                        :body '[(+ value 1)])
                 :type-dependency-fingerprints
                 :callable-dependency-fingerprints))
        target-reference
        (with-meta 'calculate
          {:aguafria/zig-reference
           {:logical-id (:logical-id target)
            :kind :fn
            :module (:module target)
            :zig-name "calculate"
            :abi-fingerprint (:abi-fingerprint target)
            :implementation-fingerprint
            (:implementation-fingerprint target)}})
        caller
        (original-info
         (assoc function-declaration
                :name 'caller
                :declaration-key [:fn 'caller]
                :body [(list target-reference 'value)]))]
    (with-redefs-fn
      {#'aguafria.zig.runtime/config
       (atom (assoc (runtime/configuration) :reloadable? true))
       #'aguafria.zig.runtime/declaration-info
       (fn [declaration]
         (swap! calls inc)
         (original-info declaration))}
      (fn []
        (testing "a selected body edit does not walk an unrelated declaration"
          (let [refreshed (refresh [target caller]
                                   #{(:declaration-key target)})]
            (is (= 1 @calls))
            (is (identical? caller (second refreshed)))))
        (reset! calls 0)
        (testing "a stable-cell implementation edit does not force a second pass"
          (let [refreshed (refresh [target caller])]
            (is (= 2 @calls))
            (is (= 1
                   (count (:callable-dependency-fingerprints
                           (second refreshed)))))
            (is (= [(:logical-id target) (:abi-fingerprint target)]
                   (first (:callable-dependency-fingerprints
                           (second refreshed)))))))))))

(deftest reference-refresh-preserves-namespace-root-import-test
  (let [refresh (var-get
                 #'aguafria.zig.runtime/refresh-live-declaration-references)
        root
        (with-meta 'dependency
          {:aguafria/zig-reference
           {:kind :namespace-root
            :module "fixture.dependency"
            :import-name "fixture.dependency"
            :import-alias "dependency"
            :import-namespace 'fixture.dependency
            :zig-name "dependency"}})
        alias
        (runtime/declaration-info
         {:module "fixture.consumer"
          :kind :const
          :name 'dependency
          :declaration-key [:const 'dependency]
          :value root})
        same-named-function
        (runtime/declaration-info
         {:module "fixture.dependency"
          :kind :fn
          :name 'dependency
          :declaration-key [:fn 'dependency]
          :args []
          :return :void
          :body []})]
    (with-redefs-fn
      {#'aguafria.zig.runtime/registered-declarations-by-logical-id
       (fn [] {(:logical-id same-named-function) same-named-function})}
      (fn []
        (let [refreshed (first (refresh [alias]))
              reference (:aguafria/zig-reference
                         (meta (:value refreshed)))]
          (is (= :namespace-root (:kind reference)))
          (is (= "fixture.dependency" (:import-name reference)))
          (is (nil? (:logical-id reference))))))))

(deftest external-generation-advertises-only-resolved-owned-getters-test
  (let [registry (var-get #'aguafria.zig.runtime/registry)
        old-registry @registry
        module "fixture.external-generation"
        generation
        {:generation 7
         :library-path "/tmp/libfixture.dylib"
         :dispatch-bindings
         {:resolved {:owned? true
                     :declaration
                     {:logical-id [module :fn "changed"]}
                     :getter "resolved_getter"
                     :setter "resolved_setter"
                     :implementation-address 4096}
          :not-emitted {:owned? true
                        :getter "missing_getter"
                        :setter "missing_setter"}
          :embedded {:owned? false
                     :getter "embedded_getter"
                     :setter "embedded_setter"
                     :implementation-address 8192}}}]
    (try
      (swap! registry assoc module
             {:published-generation 7
              :native-generations [generation]})
      (is (= [{:getter "resolved_getter" :setter "resolved_setter"}]
             (:dispatch (runtime/external-generation-info module))))
      (is (= [{:getter "resolved_getter" :setter "resolved_setter"}]
             (:dispatch
              (runtime/external-generation-info
               module #{[module :fn "changed"]}))))
      (is (empty?
           (:dispatch
            (runtime/external-generation-info
             module #{[module :fn "unrelated"]}))))
      (finally
        (reset! registry old-registry)))))

(deftest independent-hot-slice-refreshes-only-its-dependency-snapshot-test
  (let [refresh (var-get
                 #'aguafria.zig.runtime/refresh-plan-dependency-snapshots)
        calls (atom [])
        plan {:prefer-fallback? true
              :primary {:source-dirty? true
                        :declarations [:complete-module]
                        :dependency-snapshot :deferred}
              :fallback {:complete-development-root? false
                         :declarations [:edited-live-slice]
                         :dependency-snapshot :stale}}]
    (with-redefs-fn
      {#'aguafria.zig.runtime/development-dependency-snapshot
       (fn [declarations]
         (swap! calls conj declarations)
         {:fresh declarations})}
      (fn []
        (let [refreshed (refresh plan)]
          (is (= [[:edited-live-slice]] @calls))
          (is (= :deferred
                 (get-in refreshed [:primary :dependency-snapshot])))
          (is (= {:fresh [:edited-live-slice]}
                 (get-in refreshed [:fallback :dependency-snapshot]))))))))

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

(deftest const-result-of-reexported-type-factory-is-a-versioned-type-test
  (let [factory
        (runtime/declaration-info
         {:module "fixture.impl"
          :kind :fn
          :name 'Struct
          :declaration-key [:fn 'Struct]
          :args [{:name 'Target :type :type :properties {:zig/prefix "comptime"}}
                 {:name 'Zig :type :type :properties {:zig/prefix "comptime"}}]
          :return :type
          :body '[(return (switch Target
                            (case [:zig] Zig)
                            (case [:c] Zig)))]})
        alias
        (runtime/declaration-info
         {:module "fixture.api"
          :kind :const
          :name 'Struct
          :declaration-key [:const 'Struct]
          :value 'fixture.impl/Struct})
        declarations {"fixture.api/Struct" alias
                      "fixture.impl/Struct" factory}
        describe
        (fn [field-type]
          (with-redefs-fn
            {#'runtime/referenced-declaration
             (fn [_ reference] (get declarations (str reference)))}
            #(let [declaration
                   (runtime/declaration-info
                    {:module "fixture.user"
                     :kind :const
                     :name 'Payload
                     :declaration-key [:const 'Payload]
                     :value
                     (list 'fixture.api/Struct :zig
                           (list 'container {:kind :struct :layout :normal}
                                 (list 'field-decl 'value field-type)))})]
               {:declaration declaration
                :reference (#'runtime/declaration-reference-view declaration)})))
        baseline-result (describe :u32)
        changed-result (describe :u64)
        baseline (:declaration baseline-result)
        changed (:declaration changed-result)]
    (is (= 64 (count (:schema-fingerprint baseline))))
    (is (= 64 (count (:shape-fingerprint baseline))))
    (is (not= (:schema-fingerprint baseline)
              (:schema-fingerprint changed)))
    (is (not= (:implementation-fingerprint baseline)
              (:implementation-fingerprint changed)))
    (is (true? (get-in baseline-result [:reference :type-reference?])))))

(deftest reference-index-resolves-members-through-module-reexports-test
  (let [registry (var-get #'aguafria.zig.runtime/registry)
        reference-index
        (var-get #'aguafria.zig.runtime/declaration-reference-index)
        old-registry @registry
        old-index @reference-index
        reexport-root
        (with-meta 'impl-root
          {:aguafria/zig-reference
           {:kind :namespace-root
            :module "fixture.reexport.impl"
            :zig-name "impl_root"}})
        reexport-member
        (with-meta 'fixture.reexport.api/repl
          {:aguafria/zig-reference
           {:kind :namespace-member
            :module "fixture.reexport.api"
            :zig-name "api.repl"
            :symbol 'fixture.reexport.api/repl}})
        factory
        (runtime/declaration-info
         {:module "fixture.reexport.impl"
          :kind :fn
          :name 'ReplType
          :declaration-key [:fn 'ReplType]
          :args []
          :return :type
          :body '[(container {:kind :struct :layout :normal})]})
        alias
        (runtime/declaration-info
         {:module "fixture.reexport.api"
          :kind :const
          :name 'repl
          :declaration-key [:const 'repl]
          :value reexport-root})
        consumer
        (assoc
         (runtime/declaration-info
          {:module "fixture.reexport.consumer"
           :kind :fn
           :name 'start
           :declaration-key [:fn 'start]
           :args []
           :return :void
           :zig-qualifiers "!"
           :body [(list 'field reexport-member 'ReplType)]})
         ;; Exercise adoption of an index snapshot produced by the former
         ;; whole-descriptor reference walk.
         :callable-dependency-fingerprints
         [[ ["fixture.reexport.consumer" :fn "start"] "abi" "impl"]])
        definitions
        (fn [declaration]
          {(:declaration-key declaration) declaration})]
    (try
      (reset! registry
              {"fixture.reexport.impl"
               {:definitions (definitions factory)}
               "fixture.reexport.api"
               {:definitions (definitions alias)}
               "fixture.reexport.consumer"
               {:definitions (definitions consumer)}})
      (reset! reference-index
              {:by-module {} :by-logical {} :references {} :revision 0})
      ((var-get #'aguafria.zig.runtime/registered-declarations-by-logical-id))
      (is (contains?
           (get-in @reference-index
                   [:references (:logical-id consumer)])
           (:logical-id factory)))
      (is (not (contains?
                (get-in @reference-index
                        [:references (:logical-id consumer)])
                (:logical-id consumer))))
      (is ((var-get #'aguafria.zig.runtime/dispatchable-declaration?)
           consumer))
      (is ((var-get
            #'aguafria.zig.runtime/declaration-references-impact?)
           consumer
           #{{:kind :type :logical-id (:logical-id factory)} }))
      (finally
        (reset! registry old-registry)
        (reset! reference-index old-index)))))

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
