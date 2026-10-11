(ns aguafria.zig.preparation-identity-test
  (:require [aguafria.keyword]
            [aguafria.zig :as a]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.test :refer [deftest is testing]]))

(a/defstruct FirstRecord [[:value :i32]])
(a/defstruct SecondRecord [[:value :i32]])
(a/defunion ReflectedUnion {:attrs #{aguafria.keyword/enum}} [[:a :i32] [:b :bool]])
(a/defunion UntaggedUnion [[:a :i32] [:b :bool]])
(a/defenum ReflectedTags
  [:some-tag :other
   (a/fn is-some :bool [[self ReflectedTags]]
     (aguafria.keyword/== self (:some-tag ReflectedTags)))])

(deftest native-type-projections-have-one-spelling
  (let [observed '(field (field (aguafria.keyword/typeInfo [:* :u32]) "pointer") "child")
        transported '(field (field (type (aguafria.keyword/typeInfo (type [:* :u32]))) :pointer) :child)]
    (is (= (jvm/constructor-type observed) (jvm/constructor-type transported)))
    (is (= (emitter/emit-type observed) (emitter/emit-type (jvm/constructor-type observed))))
    (is (= (emitter/emit-type transported) (emitter/emit-type (jvm/constructor-type transported))))))

(deftest projection-normalization-retains-exact-member-spelling
  (is (= '(field Owner "some_field") (jvm/constructor-type '(field Owner :some-field))))
  (is (= '(field Owner "some-field") (jvm/constructor-type '(field Owner "some-field"))))
  (is (not= (jvm/constructor-type '(field Owner :some-field))
            (jvm/constructor-type '(field Owner "some-field")))))

(deftest field-plans-share-projection-spelling-but-retain-storage-qualifiers
  (let [record 'aguafria.zig.preparation-identity-test/FirstRecord
        observed (jvm/optional-payload-type [:optional [:* record]])
        transported (list 'field
                          (list 'field (list 'type (list 'aguafria.keyword/typeInfo
                                                         (list 'type [:optional [:* record]])))
                                :optional)
                          :child)
        identity (fn [type pointer-kind]
                   (runtime/adapter-fingerprint
                    (dissoc (#'jvm/field-view-plan type :value [pointer-kind type]) :context)))]
    (is (= (identity observed :*const) (identity transported :*const)))
    (is (not= (identity observed :*) (identity observed :*const)))))

(deftest field-invocation-uses-the-shared-plans-normalized-parameter-types
  (let [receiver (jvm/optional-payload-type
                  [:optional [:* 'aguafria.zig.preparation-identity-test/FirstRecord]])
        address [:*const receiver]
        expected (:types (#'jvm/field-view-plan receiver :value address))
        invoked (atom nil)]
    (with-redefs-fn
      {#'value/address-value (fn [& _] ::storage)
       #'value/info (constantly {:kind :return})
       #'value/storage-type (constantly receiver)
       #'value/retain-owners! (fn [result _] result)
       #'jvm/call-inputs (fn [& _] {:parameters [{:name 'input_0 :type address}]
                                    :arguments [::storage]})
       #'jvm/invoke-expression! (fn [_ _ parameters arguments _]
                                  (reset! invoked [parameters arguments]) ::result)}
      #(is (= ::result (#'jvm/field-storage-view! ::receiver :value))))
    (is (= expected (mapv :type (first @invoked))))
    (is (= [::storage] (second @invoked)))))

(deftest transport-does-not-query-equivalence-for-normalized-type-spellings
  (let [actual (jvm/optional-payload-type
                [:optional [:* 'aguafria.zig.preparation-identity-test/FirstRecord]])
        expected (jvm/constructor-type actual)
        queried (atom false)
        result (with-redefs-fn
                 {#'value/zig-value? #(= ::argument %)
                  #'value/storage-type (constantly actual)
                  #'value/info (constantly {:module (str *ns*)})
                  #'value/segment (fn [_] (throw (ex-info "Reached transport" {:transport? true})))
                  #'jvm/equivalent-types? (fn [& _] (reset! queried true) false)}
                 #(try (#'runtime/native-argument-address (str *ns*) 'sample expected ::argument nil nil)
                       (catch clojure.lang.ExceptionInfo e (ex-data e))))]
    (is (= {:transport? true} result))
    (is (false? @queried))))

(deftest reflected-signatures-require-complete-successful-compiler-evidence
  (let [types [(jvm/indexed-element-type '(aguafria.keyword/TypeOf SomeArray))]
        proof {:compiler-errors? false :observed {"op" #{types}}}]
    (is (#'discovery/compiler-confirmed-signature? proof "op" types))
    (is (#'discovery/compiler-confirmed-signature?
         {:compiler-errors? false :observed {7 [[{:representations [:u8 :u16]}]]}} 7 [:u16]))
    (is (not (#'discovery/compiler-confirmed-signature? proof "other" types)))
    (is (not (#'discovery/compiler-confirmed-signature?
              (assoc proof :compiler-errors? true) "op" types)))
    (is (not (#'discovery/compiler-confirmed-signature? proof "op" [:u32])))
    (is (not (#'discovery/compiler-confirmed-signature?
              {:compiler-errors? false :observed {"op" #{[nil]}}} "op" [nil])))))

(deftest enum-tag-provenance-uses-declarations-without-realizing-values
  (let [context (the-ns 'aguafria.zig.preparation-identity-test)
        source (with-redefs [value/realize! (fn [& _] (throw (Exception. "Must not read a value")))]
                 (#'discovery/declared-enum-tag-source context 'ReflectedTags :some-tag))]
    (is (= '(aguafria.keyword/TypeOf
             (aguafria.zig/field aguafria.zig.preparation-identity-test/ReflectedTags "some_tag"))
           (:root-type source)))
    (is (:retain-native-observation? source))
    (is (= '(aguafria.keyword/TypeOf
             (aguafria.zig/field aguafria.zig.preparation-identity-test/ReflectedUnion "a"))
           (:root-type (#'discovery/declared-enum-tag-source context 'ReflectedUnion :a))))
    (is (nil? (#'discovery/declared-enum-tag-source context 'UntaggedUnion :a)))
    (is (nil? (#'discovery/declared-enum-tag-source
               context (with-meta 'ReflectedTags {:aguafria/local? true}) :some-tag)))
    (is (nil? (#'discovery/declared-enum-tag-source context 'ReflectedTags "some-tag")))
    (is (nil? (#'discovery/declared-enum-tag-source context 'ReflectedTags :missing)))))

(deftest builtin-result-provenance-uses-runtime-shaped-operands
  (let [context (the-ns 'aguafria.zig.preparation-identity-test)
        source (with-redefs [value/realize! (fn [& _] (throw (Exception. "Must not read a value")))]
                 (#'discovery/jvm-value-type-source
                  context '(aguafria.keyword/tagName (field ReflectedUnion :a))))]
    (is (:retain-native-observation? source))
    (is (jvm/equivalent-types? (str (ns-name context)) (:root-type source)
                               [:* {:size :slice :sentinel 0 :const? true} :u8]))
    (is (not (jvm/equivalent-types? (str (ns-name context)) (:root-type source)
                                    [:slice-const :u8])))))

(deftest method-plans-normalize-declaration-tag-projections
  (let [plain '(aguafria.keyword/TypeOf
                (field aguafria.zig.preparation-identity-test/ReflectedTags "some_tag"))
        wrapped '(aguafria.keyword/TypeOf
                  (field (type aguafria.zig.preparation-identity-test/ReflectedTags) :some-tag))
        plan #(dissoc (#'jvm/method-plan % :is-some false true
                                         {:parameters [] :expression-arguments []}) :context)]
    (is (= (runtime/adapter-fingerprint (plan plain))
           (runtime/adapter-fingerprint (plan wrapped))))))

(deftest field-profile-uses-compiler-output-not-the-diagnostic-source-line
  (with-redefs [runtime/inspect-declarations!
                (fn [& _]
                  {:exit 1
                   :err (str "error: found compile log statement\n"
                             "if (pointer) \"aguafria.field-profile:pointer\" else ...\n"
                             "Compile Log Output:\n@as([]const u8, \"aguafria.field-profile:value\")\n")})]
    (is (= '(aguafria.keyword/FieldType :Receiver "arch")
           (#'jvm/field-inspection-type! *ns* :Receiver :arch)))))

(deftest explicit-inspection-keeps-adapter-roots-and-graph-options
  (let [graph [{:module "adapter-only" :kind :fn :name 'query :jvm-adapter? true}]
        dependencies (atom nil)]
    (with-redefs-fn
      {#'runtime/ensure-converted-dependency-sources! (fn [module declarations]
                                                        (reset! dependencies [module declarations]))
       #'runtime/inspection-options (fn [module declarations]
                                      {:module module :graph declarations :target "aarch64-macos"})
       #'runtime/inspect-module! (fn [module transform]
                                   (is (= "adapter-only" module))
                                   (is (= graph (:declarations @#'runtime/*inspection-context*)))
                                   (is (= "aarch64-macos" (get-in @#'runtime/*inspection-context* [:options :target])))
                                   (transform graph))}
      #(is (= graph (runtime/inspect-declarations! "adapter-only" graph identity))))
    (is (= ["adapter-only" graph] @dependencies))))

(deftest tuple-field-plans-preserve-type-valued-elements
  (let [type '(aguafria.keyword/Tuple
               (aguafria.keyword/& [:u32 (aguafria.zig/type [:*const [:array 2 {:sentinel 0} :u8]])]))
        plan (#'jvm/field-view-plan type :len [:*const type])
        type-expression (second (:writer plan))]
    (is (= "@Tuple((&.{u32, *const [2:0]u8}))"
           (emitter/emit-expr (the-ns 'aguafria.zig.preparation-identity-test) type-expression)))))

(deftest ordinary-call-inputs-use-the-prepared-type-normalization
  (let [type '(aguafria.zig/unwrap
               (aguafria.zig/field
                (aguafria.zig/field (aguafria.keyword/typeInfo
                                     aguafria.zig.preparation-identity-test/ReflectedUnion) :union)
                :tag_type))
        declarations [{:type :anytype}]
        prepared (#'jvm/prepared-call-inputs [type] declarations)
        actual (with-redefs [value/zig-value? #(= ::argument %)
                             value/storage-type (constantly type)
                             value/qualified-type (constantly type)
                             value/value (constantly :a)
                             value/realize! (constantly {})]
                 (#'jvm/call-inputs declarations [::argument]))]
    (is (= (:parameters prepared) (:parameters actual)))
    (is (= (:expression-arguments prepared) (:expression-arguments actual)))))

(deftest contextual-address-prepares-owning-layout-without-allocating
  (let [calls (atom [])
        initializer {:tuple [{:comptime 1} {:comptime 2}]}
        target [:slice-const :i32]
        backing (#'jvm/contextual-address-backing-type target 2)]
    (with-redefs [jvm/precompile-reflected-layout! (fn [_ t] (swap! calls conj [:layout t]))
                  jvm/precompile-construction! (fn [t input] (swap! calls conj [:construction t input]))
                  jvm/precompile-conversion! (fn [from to] (swap! calls conj [:conversion from to]))]
      (#'jvm/precompile-contextual-address! target initializer))
    (is (= [[:layout backing] [:construction backing initializer]
            [:conversion [:*const backing] target]] @calls))))

(deftest contextual-address-construction-uses-the-owning-plan
  (let [calls (atom [])
        initializer {:tuple [{:comptime 1}]}]
    (with-redefs-fn {#'jvm/precompile-contextual-address!
                     (fn [target input] (swap! calls conj [target input]))}
      #(is (= :prepared (:status (jvm/precompile-construction!
                                  [:slice-const :i32] {:contextual-address initializer})))))
    (is (= [[[:slice-const :i32] initializer]] @calls))))

(deftest indexed-and-optional-result-types-are-compiler-properties
  (doseq [[container child] [[[:array 0 :u32] :u32]
                             [[:array 10 :i32] :i32]
                             [[:vector 4 :f32] :f32]
                             [[:slice-const :u8] :u8]
                             [[:many :i16] :i16]
                             [[:*const [:array 3 :u64]] :u64]
                             [[:array 2 [:*const :u32]] [:*const :u32]]]]
    (testing (pr-str container)
      (is (true? (jvm/equivalent-types? (str *ns*) child (jvm/indexed-element-type container))))
      (is (true? (jvm/equivalent-types?
                  (str *ns*) child
                  (jvm/indexed-element-type
                   (list 'aguafria.keyword/TypeOf
                         (list 'aguafria.keyword/as 'aguafria.keyword/undefined container))))))))
  (is (true? (jvm/equivalent-types? (str *ns*) [:*const :u32]
                                    (jvm/optional-payload-type [:optional [:*const :u32]])))))

(deftest result-reflection-preserves-nominal-identity-and-never-reads-pointers
  (let [first-type 'aguafria.zig.preparation-identity-test/FirstRecord
        second-type 'aguafria.zig.preparation-identity-test/SecondRecord
        element (jvm/indexed-element-type [:array 2 first-type])]
    (is (true? (jvm/equivalent-types? (str *ns*) first-type element)))
    (is (false? (jvm/equivalent-types? (str *ns*) second-type element)))
    (is (true? (jvm/equivalent-types? (str *ns*) :i32
                                      (jvm/field-value-type [:* first-type] :value))))))
