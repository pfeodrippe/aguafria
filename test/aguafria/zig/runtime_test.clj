(ns aguafria.zig.runtime-test
  (:require [aguafria.zig]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.artifact :as artifact]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.project :as project]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest tagged-union-wrapper-flags-match-container-attributes
  (doseq [[options tagged?] [[{} false]
                             [{:attrs #{'aguafria.keyword/enum}} true]
                             [{:type :u8} true]]]
    (let [module (str (ns-name *ns*))
          declaration (emitter/prepare-declaration
                       *ns* {:module module :kind :const :name 'Payload
                             :declaration-key [:const 'Payload]
                             :value (emitter/struct-container-form
                                     (assoc options :kind :union)
                                     [[:value :u32]])})
          requests (fn [_ _] #{[:const 'Payload]})]
      (with-redefs-fn {#'runtime/jvm-wrapper-requests requests}
        (fn []
          (let [spec (get (#'runtime/jvm-type-wrapper-specs module [declaration])
                          (symbol module "Payload"))]
            (is (= :union (:container-kind spec)))
            (is (= tagged? (boolean (:tagged-union? spec))))))))))

(deftest registered-declarations-only-read-current-source-metadata
  (let [module "fixture.declaration-metadata"
        first-declaration {:name 'before :kind :const :value 1}
        next-declaration {:name 'after :kind :const :value 2}
        definitions {[:const 'before] first-declaration}
        registry (atom {module {:definitions definitions
                                :native-generations [:loaded-generation]}})
        native-inspection! (fn [& _]
                             (throw (ex-info "Source metadata inspected native state" {})))]
    (with-redefs-fn
      {#'runtime/registry registry
       #'runtime/dispatch-version-views native-inspection!
       #'runtime/native-generation-views native-inspection!}
      (fn []
        (let [snapshot (runtime/registered-declarations (symbol module))]
          (is (= (vec (vals definitions)) snapshot))
          (is (identical? first-declaration (first snapshot)))
          (swap! registry assoc-in [module :definitions] {[:const 'after] next-declaration})
          (is (= [next-declaration] (runtime/registered-declarations module)))
          (is (= [first-declaration] snapshot) "Prior snapshots remain immutable"))
        (is (= [] (runtime/registered-declarations "fixture.unregistered")))
        (is (= [:loaded-generation] (get-in @registry [module :native-generations])))))))

(deftest lazy-layout-publication-holds-the-retirement-lock
  (let [module "fixture.layout-publication"
        declaration {:module module :name 'Tag :kind :const
                     :declaration-key [:const 'Tag]}
        registry (atom {module {}})
        reached (atom false)
        stopped (ex-info "Stop before native compilation" {})]
    (with-redefs-fn
      {#'runtime/registry registry
       #'runtime/compile-published-declaration-slice!
       (fn [target _ _]
         (reset! reached true)
         (is (Thread/holdsLock (var-get #'runtime/compile-lock)))
         (is (= declaration target))
         (is (= #{[:const 'Tag]}
                (get-in @registry [module :jvm-type-declaration-keys])))
         (throw stopped))}
      #(let [error (try
                     (#'runtime/materialize-published-declaration!
                      declaration {:declarations [declaration]}
                      :jvm-type-declaration-keys)
                     (catch Throwable error error))]
         (is (identical? stopped error))
         (is @reached)))))

(deftest nominal-layout-accessors-live-with-their-callable-abi
  (let [module "fixture.retained-type"
        logical-id [module :const "Tag"]
        type-name (symbol module "Tag")
        old-type {:declaration {:logical-id logical-id :schema-fingerprint "old"}
                  :wrapper-generation 1}
        new-type {:declaration {:logical-id logical-id :schema-fingerprint "new"}
                  :wrapper-generation 2}
        caller "fixture.retained-caller"
        callable-id [caller :fn "echo"]
        version-key [callable-id "abi"]
        callable {:declaration {:logical-id callable-id :abi-fingerprint "abi"
                                :abi-type-dependency-fingerprints [[logical-id "old"]]}}
        old-arena (java.lang.foreign.Arena/ofShared)]
    (with-open [current-arena (java.lang.foreign.Arena/ofShared)]
      (try
        (let [state {:module module :published-generation 2
                     :types {type-name new-type}
                     :native-generations
                     [{:generation 1 :arena old-arena :types {type-name old-type}}
                      {:generation 2 :arena current-arena :types {type-name new-type}}]}
              registry (atom {module state
                              caller {:functions {(symbol caller "echo") callable}
                                      :dispatch-state {version-key {}}}})]
          (with-redefs-fn
            {#'runtime/registry registry
             #'runtime/native-host-active? (constantly false)}
            (fn []
              (let [retained (#'runtime/retire-quiescent-generations! state)]
                (is (= [1 2] (mapv :generation (:native-generations retained))))
                (is (.isAlive (.scope old-arena)))
                (swap! registry assoc-in [caller :dispatch-state] {})
                (let [retired (#'runtime/retire-quiescent-generations! retained)]
                  (is (= [2] (mapv :generation (:native-generations retired))))
                  (is (= [1] (mapv :generation (:retired-generations retired))))
                  (is (not (.isAlive (.scope old-arena))))
                  (is (.isAlive (.scope current-arena))))))))
        (finally
          (when (.isAlive (.scope old-arena)) (.close old-arena)))))))

(deftest private-adapter-publication-preserves-unchanged-callable-snapshots
  (let [declaration (runtime/declaration-info
                     {:module "fixture.snapshot-owner" :kind :fn :name 'answer
                      :declaration-key [:fn 'answer] :return :i32 :args [] :body [42]})
        key (:declaration-key declaration)
        initial {:declarations [declaration] :root :original}
        expanded {:declarations [declaration] :root :adapter
                  :jvm-adapter-publication? true}
        current {:declaration-compilation-snapshots {key initial}}
        reconcile (fn [snapshot declaration]
                    (get (#'runtime/reconciled-declaration-snapshots
                          current {:compilation-snapshot snapshot
                                   :loaded-declarations [declaration]}) key))]
    (is (identical? initial (reconcile expanded declaration)))
    (is (identical? expanded
                    (reconcile expanded (assoc declaration :implementation-fingerprint "changed"))))
    (is (identical? expanded
                    (reconcile expanded (assoc declaration :abi-fingerprint "changed"))))
    (let [source-edit (dissoc expanded :jvm-adapter-publication?)]
      (is (identical? source-edit (reconcile source-edit declaration))))))

(deftest private-adapter-publication-preserves-unchanged-constructor-snapshots
  (let [declaration (runtime/declaration-info
                     {:module "fixture.snapshot-layout" :kind :struct :name 'Item
                      :declaration-key [:struct 'Item]
                      :fields [{:name :value :type :u32}]})
        key (:declaration-key declaration)
        initial {:declarations [declaration] :root :original}
        expanded {:declarations [declaration] :root :adapter
                  :jvm-adapter-publication? true}
        current {:declaration-compilation-snapshots {key initial}}
        reconcile (fn [snapshot declaration]
                    (get (#'runtime/reconciled-declaration-snapshots
                          current {:compilation-snapshot snapshot
                                   :loaded-declarations [declaration]}) key))]
    (is (identical? initial (reconcile expanded declaration)))
    (is (identical? expanded
                    (reconcile expanded (assoc declaration :schema-fingerprint "changed"))))
    (is (identical? expanded
                    (reconcile expanded (assoc declaration :implementation-fingerprint "changed"))))
    (let [source-edit (dissoc expanded :jvm-adapter-publication?)]
      (is (identical? source-edit (reconcile source-edit declaration))))))

(deftest state-references-only-fingerprint-mutable-declarations
  (with-redefs [runtime/declaration-info
                (fn [_] (throw (ex-info "Non-state declaration was fingerprinted" {})))]
    (doseq [kind [:fn :fn-proto :const :struct :import :raw :field :extern-var]]
      (is (nil? (runtime/state-reference {:kind kind})))))
  (let [variable {:kind :var :module "fixture.state-reference" :name 'counter
                  :declaration-key [:var 'counter] :type :u32 :value 0 :align 16}
        descriptor (runtime/declaration-info variable)
        expected (select-keys (#'runtime/declaration-state-spec descriptor)
                              [:version-key :logical-id :schema-fingerprint :accessor])
        tls (runtime/declaration-info (assoc variable :zig-prefix "threadlocal"))]
    (is (= expected (runtime/state-reference variable)))
    (is (= expected (runtime/state-reference descriptor)))
    (is (= {:accessor (str (:name (#'runtime/threadlocal-accessor-declaration tls)))}
           (runtime/state-reference tls))))
  (let [calls (atom 0)]
    (with-redefs [runtime/state-reference
                  (fn [_] (swap! calls inc) {:accessor "state_accessor"})]
      (is (= "state_accessor"
             (:state-accessor (runtime/declaration-reference
                               {:kind :var :module "fixture.state-reference" :name 'counter}))))
      (is (= 1 @calls) "Publishing metadata computes a state reference once"))))

(deftest preparation-retains-refreshed-identities-without-publishing
  (let [module "fixture.prepared-types"
        declaration {:module module :kind :struct :name 'Item
                     :declaration-key [:struct 'Item]
                     :schema-fingerprint "before-dependency-load"}
        refreshed (assoc declaration :schema-fingerprint "complete-dependencies")
        state {:source-only? true :definitions {[:struct 'Item] declaration}}
        registry (atom {module state})
        indexed (atom [])
        metadata (atom [])
        plan {:primary {:declarations [refreshed]}}]
    (with-redefs-fn
      {#'runtime/registry registry
       #'runtime/ensure-converted-dependency-sources! (fn [& _])
       #'runtime/compilation-plan (fn [& _] plan)
       #'runtime/refresh-plan-dependency-snapshots identity
       #'runtime/index-declarations-incrementally! #(reset! indexed %)
       #'runtime/publish-clojure-declaration-metadata! #(reset! metadata %)
       #'runtime/compile-plan!
       (fn [_ input]
         (is (= refreshed (get-in @registry [module :definitions [:struct 'Item]])))
         input)}
      (fn []
        (is (= plan (#'runtime/precompile-declaration-generation! declaration nil)))
        (is (= [refreshed] @indexed))
        (is (= [refreshed] @metadata))
        (is (= (assoc-in state [:definitions [:struct 'Item]] refreshed)
               (get @registry module))
            "Preparing source identities must not create native generations or handles")
        (let [published (assoc state :generation 1 :library :existing-native-library)]
          (reset! registry {module published})
          (reset! indexed [])
          (#'runtime/retain-prepared-source-identities! module published plan)
          (is (= published (get @registry module)))
          (is (empty? @indexed) "Published identities remain untouched"))))))

(deftest callable-preparation-covers-published-and-source-only-demand-paths
  (doseq [[kind request-key getters]
          [[:fn :jvm-callable-declaration-keys [#{} #{[:fn 'call]}]]
           [:struct :jvm-type-declaration-keys [#{}]]]]
    (let [module "fixture.prepared-call-paths"
          declaration {:module module :kind kind :name 'call
                       :declaration-key [:fn 'call]}
          registry (atom {module {:source-only? true
                                  :definitions {[:fn 'call] declaration}}})
          image {:snapshot {:declarations [declaration]}
                 :dispatch-specs {[:fn 'call] {:emit-getter? true}}}
          paths (atom [])
          plan {:primary {:declarations [declaration]}}]
      (with-redefs-fn
        {#'runtime/registry registry
         #'runtime/ensure-converted-dependency-sources! (fn [& _])
         #'runtime/native-declaration-equivalent? =
         #'runtime/compile-published-declaration-slice!
         (fn [compiled snapshot getters]
           (is (= declaration compiled))
           (is (= (:snapshot image) snapshot))
           (swap! paths conj [:published getters]))
         #'runtime/compilation-plan (fn [& _] plan)
         #'runtime/refresh-plan-dependency-snapshots identity
         #'runtime/retain-prepared-source-identities! (fn [& _])
         #'runtime/compile-plan! (fn [_ input]
                                   (swap! paths conj :source-only-demand)
                                   input)}
        #(binding [runtime/*prepared-namespace-images* (atom {module image})]
           (is (= plan (#'runtime/precompile-declaration-generation!
                        declaration request-key)))
           (is (= (conj (mapv (fn [keys] [:published keys]) getters) :source-only-demand) @paths))
           (is (nil? (get-in @registry [module :generation])))
           (is (nil? (get-in @registry [module :functions]))))))))

(deftest converted-dependency-loading-preserves-registered-source-only-definitions
  (let [module "fixture.converted-root"
        dependency "fixture.converted-peer"
        cleanup {:kind :fn :name '__jvm_release}
        state {:definitions {[:fn '__jvm_release] cleanup}
               :source nil :source-only? true :source-dirty? true}
        registry (atom {module state dependency {:definitions {}}})
        visited (atom [])]
    (with-redefs-fn
      {#'runtime/registry registry
       #'runtime/converted-project-dependencies
       (fn [current]
         (swap! visited conj current)
         (if (= module current) [dependency] []))}
      #(do
         (#'runtime/load-converted-source-only! (java.io.File. "test") module)
         (is (= [module dependency] @visited))
         (is (= cleanup (get-in @registry [module :definitions [:fn '__jvm_release]])))
         (is (nil? (get-in @registry [module :source])))
         (is (true? (get-in @registry [module :source-dirty?])))
         (is (true? (get-in @registry [module :converted-dependency-closure-loaded?])))
         (is (true? (get-in @registry [dependency :converted-dependency-closure-loaded?])))
         (#'runtime/load-converted-source-only! (java.io.File. "test") module)
         (is (= [module dependency] @visited) "A completed closure is not traversed again")))))

(deftest adapter-compilation-retains-its-published-inputs
  (let [local-type {:kind :const :name 'Local :module "fixture.adapter"
                    :schema-fingerprint "local-v1" :value '(type :u32)}
        imported-type {:kind :const :name 'Imported :module "fixture.types"
                       :schema-fingerprint "imported-v1" :value '(type :u64)}
        slice {:declarations [local-type]
               :compile-source "adapter source"
               :development-root-source "published root"
               :development-root-declarations [local-type]
               :development-root-dependencies ["fixture.types"]
               :dependency-snapshot
               {"fixture.types" {:source "published dependency"
                                 :type-declarations [imported-type]}}}
        inputs (atom nil)
        compilation
        (with-redefs-fn
          {#'runtime/compile-source!
           (fn [& arguments]
             (reset! inputs arguments)
             {:library-path "adapter.dylib"})}
          #(#'runtime/compile-slice! "fixture.adapter" slice))
        snapshot (get-in compilation [:compiled :compilation-snapshot])]
    (is (= ["fixture.adapter" (:compile-source slice) (:declarations slice)
            (:dependency-snapshot slice) (:development-root-source slice)
            (:development-root-dependencies slice) (:development-root-declarations slice)]
           @inputs))
    (is (= (dissoc slice :compile-source)
           (dissoc snapshot :materialization-type-declarations
                   :jvm-adapter-publication?)))
    (is (false? (:jvm-adapter-publication? snapshot)))
    (with-bindings {#'runtime/*materialize-declaration* {:jvm-adapter? true}}
      (with-redefs-fn
        {#'runtime/compile-source! (fn [& _] {:library-path "adapter.dylib"})}
        #(is (true? (get-in (#'runtime/compile-slice! "fixture.adapter" slice)
                            [:compiled :compilation-snapshot
                             :jvm-adapter-publication?])))))
    (is (= {["fixture.adapter" 'Local] local-type
            ["fixture.types" 'Imported] imported-type}
           (:materialization-type-declarations snapshot)))
    (is (= "adapter.dylib" (get-in compilation [:compiled :library-path])))
    (with-bindings {#'runtime/*materialization-type-declarations*
                    (:materialization-type-declarations snapshot)}
      (with-redefs-fn
        {#'runtime/referenced-declaration
         (fn [& _] (throw (ex-info "Must not read a newer type declaration" {})))}
        #(do
           (is (= :u32 (#'runtime/bridge-storage-type "fixture.adapter" 'Local #{})))
           (is (= :u64 (#'runtime/bridge-storage-type "fixture.adapter" 'fixture.types/Imported #{}))))))))

(deftest registered-empty-modules-remain-in-development-dependencies
  (let [root {:kind :const :name 'empty-module :module "fixture.root"
              :value (with-meta 'empty-module
                       {:aguafria/zig-reference
                        {:kind :namespace-root :module "fixture.empty"
                         :zig-name "empty_module"
                         :import-alias "empty_module"
                         :import-name "fixture.empty"
                         :import-namespace 'fixture.empty}})}
        snapshot (#'runtime/development-dependency-snapshot
                  [root] {"fixture.empty" {:definitions {}}})
        entry (get snapshot "fixture.empty")]
    (is (string? (:source entry)))
    (is (str/includes? (:source entry) "__aguafria_type__fixture.empty"))
    (is (empty? (:dependencies entry)))
    (is (empty? (:dispatch-entries entry)))
    (is (empty? (#'runtime/development-dependency-snapshot [root] {})))))

(deftest dependency-facades-do-not-depend-on-owner-compilation-history
  (let [module "fixture.dependency-history"
        declaration (#'runtime/declaration-info
                     {:module module :kind :const :name 'value
                      :declaration-key [:const 'value] :type :u32 :value 42})
        registered {:definitions {[:const 'value] declaration}}
        published (assoc registered
                         :dependency-source "previously emitted owner facade"
                         :source-dirty? false)
        entry #(select-keys
                (#'runtime/development-dependency-entry module % (constantly []))
                [:source :source-fingerprint])]
    (is (= (entry registered) (entry published)))
    (is (= (emitter/emit-dependency-module module [declaration])
           (:source (entry published))))))

(deftest retained-adapter-root-keeps-its-generated-module-imports
  (let [caller {:module "fixture.adapter" :kind :fn :name 'answer
                :args [] :return :u32 :body [42]}
        imported {:module "fixture.adapter" :kind :const :name 'generated
                  :value '(aguafria.keyword/import "generated")}
        options
        (with-redefs-fn
          {#'project/generated-modules
           (fn [module] (when (= module "fixture.adapter") {"generated" "pub const n = 7;"}))
           #'runtime/materialize-module-source! (fn [module _] (str module ".zig"))}
          #(#'runtime/compiler-options-for-declarations
            {:development-dependencies? true
             :development-root-source "published root"
             :development-root-declarations [caller imported]
             :development-root-dependencies []
             :dependency-snapshot {}}
            [caller]))]
    (is (= "build-generated-generated.zig" (get-in options [:modules "generated"])))
    (is (= ["generated"] (get-in options [:module-dependencies "fixture.adapter"])))
    (is (not (contains? options :development-root-declarations)))))

(deftest retained-adapter-root-keeps-its-type-dependencies
  (let [callable {:module "fixture.adapter" :kind :fn :name 'answer}
        retained {:module "fixture.adapter" :kind :var :name 'state}
        observed (atom nil)
        stop (ex-info "Captured compiler inputs" {})]
    (with-redefs-fn
      {#'runtime/extend-development-dependency-snapshot (fn [snapshot & _] snapshot)
       #'runtime/development-linkage-logical-ids
       (fn [declarations]
         (cond-> #{:callable-dependency}
           (some #{retained} declarations) (conj :retained-state-type)))
       #'runtime/development-capsule-closure
       (fn [_ logical-ids _ _]
         (reset! observed logical-ids)
         (throw stop))}
      #(is (identical? stop
                       (try
                         (#'runtime/compile-source! "fixture.adapter" "adapter"
                                                    [callable] {} "published root" [] [callable retained])
                         (catch Exception error error)))))
    (is (= #{:callable-dependency :retained-state-type} @observed))))

(deftest retained-adapter-root-keeps-lazy-constant-source-prerequisites
  (let [callable {:module "fixture.adapter" :kind :fn :name 'answer}
        retained {:module "fixture.adapter" :kind :const :name 'SelectedType}
        observed (atom nil)
        stop (ex-info "Captured compiler inputs" {})]
    (with-redefs-fn
      {#'runtime/extend-development-dependency-snapshot (fn [snapshot & _] snapshot)
       #'runtime/development-linkage-logical-ids (constantly #{:callable-dependency})
       #'runtime/declaration-reference-logical-ids
       (fn [declaration]
         (if (= retained declaration) #{:foreign-type-factory} #{}))
       #'runtime/development-capsule-closure
       (fn [_ linkage-ids _ source-ids]
         (reset! observed {:linkage linkage-ids :source source-ids})
         (throw stop))}
      #(is (identical? stop
                       (try
                         (#'runtime/compile-source! "fixture.adapter" "adapter"
                                                    [callable] {} "published root" []
                                                    [callable retained])
                         (catch Exception error error)))))
    (is (= #{:foreign-type-factory} (:source @observed)))
    (is (= #{:callable-dependency} (:linkage @observed)))))

(deftest root-context-scan-requires-an-import-and-keeps-member-semantics
  (let [members #'runtime/root-context-member-names
        source (str "const root = @import(\"root\");\n"
                    "const cycle = @import(\"app.special+name\").@\"container\";\n"
                    "root.options; root.@\"quoted member\";\n"
                    "@hasDecl(root, \"has_option\"); @field(root, \"field_option\");\n"
                    "cycle.build_options; @field(cycle, \"cyclic_option\");\n"
                    "@import(\"root\").direct;\n")
        scanned (atom [])
        re-seq* re-seq]
    (is (= #{"options" "quoted member" "has_option" "field_option"
             "build_options" "cyclic_option" "direct"}
           (members "app.special+name" {"dep" {:source source}})))
    (is (= #{"options" "quoted member" "has_option" "field_option" "direct"}
           (members "another.module" {"dep" {:source source}})))
    (is (= #{} (members "app.special+name" {})))
    (with-redefs [re-seq (fn [pattern text]
                           (swap! scanned conj text)
                           (re-seq* pattern text))]
      (is (= #{} (members "app.special+name"
                          {"no-source" {}
                           "unrelated" {:source "const dep = @import(\"other\"); dep.item;"}
                           "similar-name" {:source "const dep = @import(\"app.specialXname\"); dep.item;"}})))
      (is (empty? @scanned) "Sources without a matching import need no regex scans"))
    (is (= #{"fresh"}
           (members "app.special+name"
                    {"dep" {:source "@import(\"root\").fresh;"}})))
    (is (= #{} (members "app.special+name" {"dep" {:source ""}})))))

(deftest fingerprint-sorting-preserves-order-and-prints-each-key-once
  (let [sort-fingerprints #'runtime/sorted-fingerprints
        inputs [nil [] [["module" :fn "only"]]
                [[[:module :fn "z"] "abc" nil]
                 [[:module :struct "a"] "def" "shape"]
                 [[:module :fn "z"] "abc" nil]
                 [[:module :fn "a"] "ghi"]]]]
    (doseq [input inputs]
      (let [expected (vec (sort-by pr-str input))
            printed (atom [])
            original pr-str
            actual (with-redefs [pr-str (fn [value]
                                          (swap! printed conj value)
                                          (original value))]
                     (sort-fingerprints input))]
        (is (= expected actual))
        (is (vector? actual))
        (is (= (vec input) @printed))))))

(deftest fingerprint-sort-keys-reuse-only-complete-metadata-free-printing
  (let [sort-fingerprints #'runtime/sorted-fingerprints
        shared [[[:module :struct "a"] "schema-a" "implementation-a" "shape-a"]
                [[:module :struct "z"] "schema-z" "implementation-z" "shape-z"]]
        changed (assoc-in shared [1 2] "new-implementation")
        printed (atom [])
        original pr-str]
    (with-bindings {#'runtime/*fingerprint-sort-keys* (atom {})}
      (with-redefs [pr-str (fn [value]
                             (swap! printed conj value)
                             (original value))]
        (is (= shared (sort-fingerprints (reverse shared))))
        (is (= shared (sort-fingerprints shared)))
        (is (= 2 (count @printed)))
        (is (= changed (sort-fingerprints (reverse changed))))
        (is (= 3 (count @printed)) "A changed identity needs a new key")
        (binding [*print-namespace-maps* false]
          (is (= shared (sort-fingerprints shared))))
        (is (= 5 (count @printed)) "Namespace-map printer modes do not share keys")
        (doseq [settings [{#'*print-length* 1}
                          {#'*print-level* 1}
                          {#'*print-meta* true}
                          {#'*print-dup* true}
                          {#'*print-readably* false}]]
          (with-bindings settings
            (let [before (count @printed)
                  expected (vec (sort-by original shared))]
              (is (= expected (sort-fingerprints shared)))
              (is (= (+ before 2) (count @printed))))))))
    (is (nil? (var-get #'runtime/*fingerprint-sort-keys*)))
    (let [a (with-meta [:same] {:line 1})
          b (with-meta [:same] {:line 2})]
      (with-bindings {#'runtime/*fingerprint-sort-keys* (atom {})
                      #'*print-meta* true}
        (is (= (vec (sort-by pr-str [b a])) (sort-fingerprints [b a])))))))

(deftest fingerprint-refresh-sort-key-storage-is-per-calculation
  (let [declaration (runtime/declaration-info
                     {:module "fixture.sort-key-scope" :kind :fn :name 'f
                      :declaration-key [:fn 'f] :args [] :return :i32 :body [42]})
        observed (atom [])
        sort-fingerprints @#'runtime/sorted-fingerprints]
    (with-redefs-fn
      {#'runtime/registered-declaration-index
       (constantly {:by-logical {} :by-module {}})
       #'runtime/sorted-fingerprints
       (fn [fingerprints]
         (swap! observed conj (var-get #'runtime/*fingerprint-sort-keys*))
         (sort-fingerprints fingerprints))}
      (fn []
        (dotimes [_ 2]
          (let [result (#'runtime/refresh-live-declaration-references-uncached
                        [(assoc declaration :type-dependency-fingerprints
                                [[[:outside :struct "T"] "schema" "implementation" "shape"]])])]
            (is (= 1 (count result)))))))
    (is (seq @observed))
    (is (every? #(instance? clojure.lang.Atom %) @observed))
    (is (= 2 (count (set @observed))) "Each calculation owns its storage")
    (is (nil? (var-get #'runtime/*fingerprint-sort-keys*)))))

(deftest inspection-without-generics-does-not-refresh-the-global-index
  (with-redefs-fn
    {#'runtime/registered-declaration-index
     (fn [] (throw (ex-info "Unexpected declaration-index refresh" {})))}
    #(is (= [] (runtime/inspection-callers [])))))

(deftest declaration-name-lookups-reuse-the-immutable-registry-index
  (let [declarations (mapv (fn [n]
                             {:module (str "fixture.names." (quot n 100))
                              :name (symbol (str "item-" n))
                              :zig-name (str "item_" n)
                              :logical-id [:fixture n]})
                           (range 1000))
        names #'runtime/declaration-index-names
        index {:by-logical (into {} (map (juxt :logical-id identity)) declarations)
               :by-module (reduce (fn [index declaration]
                                    (reduce #(assoc-in %1 [(:module declaration) :by-name %2] declaration)
                                            index (names declaration)))
                                  {} declarations)}
        replacement (assoc (first declarations) :name 'renamed :zig-name "renamed_native")
        added {:module "fixture.new" :name 'added :zig-name "added" :logical-id [:new 1]}
        local [replacement added]
        visited (atom [])
        original @names
        lookup (with-redefs-fn
                 {names (fn [declaration]
                          (swap! visited conj (:logical-id declaration))
                          (original declaration))}
                 #(#'runtime/declaration-name-lookup index local))
        expected (into {}
                       (mapcat (fn [declaration]
                                 (map #(vector [(:module declaration) %] declaration)
                                      (names declaration))))
                       (vals (merge (:by-logical index)
                                    (into {} (map (juxt :logical-id identity)) local))))]
    (is (= (mapv :logical-id local) @visited)
        "Only the local overlay may rebuild names, not all registered declarations")
    (doseq [[key declaration] expected]
      (is (= declaration (lookup key)) (pr-str key)))
    (is (nil? (lookup [(:module replacement) "item-0"])))
    (is (nil? (lookup [(:module replacement) "item_0"])))
    (is (nil? (lookup ["absent" "item-1"])))
    (let [changed (assoc-in index [:by-module "fixture.names.0" :by-name "item-1"] replacement)]
      (is (= (second declarations) (lookup ["fixture.names.0" "item-1"])))
      (is (= replacement ((#'runtime/declaration-name-lookup changed [])
                          ["fixture.names.0" "item-1"]))))))

(deftest batch-validation-preserves-declaration-order
  (let [module (str "aguafria.batch-order-" (random-uuid))
        context (create-ns (symbol module))
        registry (var-get #'runtime/registry)
        declaration (fn [order name value]
                      {:module module :kind :const :name name :type :i32
                       :value value :source-order order
                       :declaration-key [:const name]})
        initial [(declaration 0 'base 1)
                 (declaration 1 'first-value 'base)
                 (declaration 2 'second-value 'first-value)]]
    (try
      (is (= 3 (:declaration-count (runtime/register-batch! initial {}))))
      (is (= 4 (:declaration-count
                (runtime/register-batch! [(declaration 3 'appended 'second-value)]
                                         {:replace? false}))))
      (let [before (:definitions (get @registry module))]
        (doseq [options [{:replace? true} {:replace? false}]]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"[Uu]nresolved|[Uu]nknown"
               (runtime/register-batch! [(declaration 4 'premature 'later)
                                         (declaration 5 'later 2)] options))))
        (is (identical? before (:definitions (get @registry module)))
            "Rejected batches do not replace the registered definitions")
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"[Uu]nresolved|[Uu]nknown"
             (runtime/register-batch! [(declaration 0 'replacement 'base)]
                                      {:replace? true}))))
      (finally
        (swap! registry dissoc module)
        (remove-ns (ns-name context))))))

(deftest source-only-batches-emit-only-static-source
  (let [module (str "aguafria.batch-lazy-source-" (random-uuid))
        context (create-ns (symbol module))
        registry (var-get #'runtime/registry)
        declaration (fn [value]
                      {:module module :kind :const :name 'answer :type :i32
                       :value value :declaration-key [:const 'answer]})
        original @#'runtime/emit-source!
        emissions (atom [])]
    (try
      (with-redefs-fn
        {#'runtime/emit-source!
         (fn [module declarations]
           (swap! emissions conj module)
           (original module declarations))}
        (fn []
          (is (:source-only? (runtime/register-batch! [(declaration 42)] {})))
          (is (= [module] @emissions))
          (is (false? (:source-dirty? (get @registry module))))
          (is (nil? (:reload-source (get @registry module))))
          (is (nil? (:dependency-source (get @registry module))))
          (let [source (runtime/source module)]
            (is (= (emitter/emit-module module (vals (:definitions (get @registry module))))
                   source))
            (is (= [module] @emissions))
            (is (= source (runtime/source module)))
            (is (= [module] @emissions)))
          (runtime/register-batch! [(declaration 43)] {})
          (is (= [module module] @emissions))
          (is (str/includes? (runtime/source module) "43"))
          (is (= [module module] @emissions))))
      (finally
        (swap! registry dissoc module)
        (remove-ns (ns-name context))))))

(deftest inspection-loads-converted-dependencies-before-snapshotting
  (let [module "fixture.inspection-order"
        registry (var-get #'runtime/registry)
        declaration {:module module :kind :fn :name 'answer
                     :declaration-key [:fn 'answer] :args [] :return :u32 :body [42]}
        steps (atom [])]
    (try
      (swap! registry assoc module {:definitions {[:fn 'answer] declaration}})
      (with-redefs-fn
        {#'runtime/ensure-converted-dependency-sources!
         (fn [actual declarations]
           (is (= module actual))
           (is (= [declaration] (vec declarations)))
           (swap! steps conj :load))
         #'runtime/static-dependency-snapshot
         (fn [& _] (swap! steps conj :snapshot) {})
         #'runtime/compiler-options-for-declarations
         (fn [& _] {})}
        #(runtime/call-with-inspection-context module (constantly nil)))
      (is (= [:load :snapshot] @steps))
      (finally
        (swap! registry dissoc module)))))

(deftest async-registration-loads-converted-dependencies-before-planning
  (let [module "fixture.async-dependency-order"
        registry (var-get #'runtime/registry)
        declaration {:module module :kind :fn :name 'answer
                     :declaration-key [:fn 'answer] :args [] :return :u32 :body [42]}
        steps (atom [])
        stop (ex-info "Plan captured" {})]
    (try
      (with-redefs-fn
        {#'runtime/ensure-converted-dependency-sources!
         (fn [actual declarations]
           (is (= module actual))
           (is (= [declaration] (mapv #(dissoc % :source-order) declarations)))
           (is (not (Thread/holdsLock (var-get #'runtime/compile-lock))))
           (swap! steps conj :load))
         #'runtime/compilation-plan
         (fn [& _]
           (swap! steps conj :plan)
           (throw stop))}
        #(is (identical? stop
                         (try
                           (#'runtime/register-converted-async! declaration)
                           (catch Exception error error)))))
      (is (= [:load :plan] @steps))
      (finally
        (swap! registry dissoc module)))))

(deftest collection-scopes-validate-immediately-and-stay-module-local
  (let [module (str "aguafria.collection-" (random-uuid))
        other (str module ".other")
        contexts (mapv #(create-ns (symbol %)) [module other])
        registry (var-get #'runtime/registry)
        batch (runtime/registration-batch)
        declaration (fn [module name value]
                      {:module module :kind :const :name name :type :i32
                       :value value :declaration-key [:const name]})]
    (try
      (binding [runtime/*registration-batch* batch]
        (runtime/register-declaration! (declaration module 'base 1))
        (runtime/register-declaration! (declaration module 'next-value 'base))
        (is (= '#{base next-value} (get-in @batch [:scopes module :names])))
        (doseq [invalid [(declaration module 'premature 'later)
                         (declaration other 'cross-module 'base)]]
          (let [before @batch]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unresolved Zig reference"
                                  (runtime/register-declaration! invalid)))
            (is (identical? before @batch) "Failed validation cannot add a name")))
        (runtime/register-declaration! (declaration other 'local 2))
        (is (= '#{local} (get-in @batch [:scopes other :names])))
        ;; Registry changes during collection must invalidate the cached base.
        (swap! registry assoc-in [module :definitions [:const 'external]]
               (declaration module 'external 3))
        (runtime/register-declaration! (declaration module 'uses-external 'external))
        (swap! registry update-in [module :definitions] dissoc [:const 'external])
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unresolved Zig reference"
                              (runtime/register-declaration!
                               (declaration module 'stale-external 'external))))
        (runtime/register-declaration! (declaration module 'still-known 'next-value)))
      (is (= '[base next-value local uses-external still-known]
             (mapv :name (runtime/collected-declarations batch))))
      (finally
        (swap! registry dissoc module other)
        (doseq [context contexts] (remove-ns (ns-name context)))))))

(deftest collection-scopes-reuse-unchanged-registered-definitions
  (let [registry (var-get #'runtime/registry)
        module (str "aguafria.collection-cost-" (random-uuid))
        batch (runtime/registration-batch)
        original-vals vals
        scans (atom 0)
        definitions {[:const 'existing] {:name 'existing}}
        observed-scopes (atom [])]
    (try
      (swap! registry assoc-in [module :definitions] definitions)
      (with-redefs [vals (fn [m]
                           (when (identical? m definitions) (swap! scans inc))
                           (original-vals m))
                    emitter/validate-declaration-references!
                    (fn [_ declaration names]
                      (swap! observed-scopes conj (count names)) declaration)]
        (binding [runtime/*registration-batch* batch]
          (dotimes [i 1000]
            (#'runtime/collect-declaration!
             {:module module :name (symbol (str "item-" i))}))))
      (is (= 1 @scans) "Do not scan every preceding declaration for every form")
      (is (= (vec (range 1 1001)) @observed-scopes))
      (is (= 1000 (count (runtime/collected-declarations batch))))
      (finally (swap! registry dissoc module)))))

(deftest function-signatures-do-not-materialize-parameter-storage
  (doseq [type [[:fn {} [{:type :i32}] :i32]
                [:fn {:callconv :.c} [{:name :argument :type 'Missing}] 'Missing]
                [:*const [:fn {} [{:type 'Missing}] 'Missing]]]]
    (is (false? (#'runtime/ensure-native-type-binding! "fixture" type)))))

(deftest adapter-identity-normalizes-absent-source-order
  (let [reference {:kind :const :module "fixture" :zig-name "Value"}
        form (fn [order]
               (with-meta 'fixture/Value
                 {:aguafria/zig-reference (merge reference order)}))]
    (is (= (runtime/adapter-fingerprint (form {}))
           (runtime/adapter-fingerprint (form {:source-order nil}))))
    (is (not= (runtime/adapter-fingerprint (form {}))
              (runtime/adapter-fingerprint (form {:source-order 0}))))
    (is (not= (runtime/adapter-fingerprint (form {:source-order 0}))
              (runtime/adapter-fingerprint (form {:source-order 1}))))))

(deftest native-identities-do-not-depend-on-repl-print-settings
  (let [form [(with-meta 'fixture/value {:zig/type :u32})
              '(aguafria.keyword/+ left right)
              {:fixture/one [1 2 3] :fixture/two "hello ☔"}
              #{[:long-key 1 2 3] [:long-key 4 5 6]}]
        declaration {:module "fixture.printer" :kind :fn :name 'identity
                     :declaration-key [:fn 'identity]
                     :args [{:name 'x :type :u32}] :return :u32 :body '[x]}
        variable (assoc declaration :kind :var :name 'counter
                        :declaration-key [:var 'counter] :type :u32 :value 1)
        identities (fn []
                     [(runtime/adapter-fingerprint form)
                      (runtime/declaration-info declaration)
                      (#'runtime/declaration-dispatch-spec
                       (runtime/declaration-info declaration))
                      (#'runtime/declaration-state-spec
                       (runtime/declaration-info variable))])
        normal (identities)]
    (is (= "79d0041715ee380c10e70887f747437e8583d983be04115cae95664ffc676050"
           (runtime/adapter-fingerprint '(aguafria.keyword/+ left right)))
        "Normal printer defaults retain the existing adapter key")
    (doseq [settings [{#'*print-length* 1}
                      {#'*print-level* 1}
                      {#'*print-meta* true}
                      {#'*print-dup* true}
                      {#'*print-readably* false}
                      {#'*print-namespace-maps* false}
                      {#'*print-length* 1 #'*print-level* 1
                       #'*print-meta* true #'*print-dup* true
                       #'*print-readably* false #'*print-namespace-maps* false}]]
      (is (= normal (with-bindings settings (identities)))
          (str "Native identities ignore " (mapv str (keys settings)))))))

(deftest adapter-identity-uses-explicit-declaration-kind
  (let [reference {:kind :fn :module "fixture" :zig-name "make"
                   :logical-id ["fixture" :fn "make"]}
        fingerprint (fn [reference]
                      (runtime/adapter-fingerprint
                       (with-meta 'fixture/make {:aguafria/zig-reference reference})))]
    (doseq [kind [:declaration :namespace-member]]
      (is (= (fingerprint reference) (fingerprint (assoc reference :kind kind)))))
    (is (not= (fingerprint reference)
              (fingerprint (assoc reference :logical-id ["fixture" :const "make"]))))
    (is (not= (fingerprint reference)
              (fingerprint (assoc reference :zig-name "imported.make"))))))

(deftest diagnostic-frames-use-original-forms-generically
  (let [source "(let [value 0]\n  (consume\n    value\n    false))\n"]
    (with-redefs-fn
      {#'runtime/source-text (constantly source)}
      (fn []
        (let [frame (#'runtime/clojure-code-frame "any-source.clj" 2 3 "offending form")]
          (is (str/includes? frame "2 |   (consume\n   |   ^^^^^^^^"))
          (is (str/includes? frame "3 |     value\n   |     ^^^^^"))
          (is (str/includes? frame "4 |     false))\n   |     ^^^^^^ offending form")
              "The enclosing let's closing delimiter is not part of the offending form.")
          (doseq [message ["expected type 'i32', found 'bool'"
                           "use of undeclared identifier 'missing'"
                           "overflow of integer type 'u8'"]]
            (let [rendered (#'runtime/format-zig-diagnostic
                            {:file "generated.zig" :line 9 :column 4
                             :severity :error :message message
                             :aguafria/source {:file "any-source.clj" :line 2 :column 3}})]
              (is (str/includes? rendered message))
              (is (str/includes? rendered "any-source.clj:2:3"))
              (is (str/includes? rendered "4 |     false))"))))
          (let [form (with-meta '(consume value false) {:line 2 :column 3})
                rendered (#'runtime/pretty-emission-error
                          {:module "fixture" :name 'consumer
                           :source {:file "any-source.clj" :line 1 :column 1}}
                          (ex-info "Unsupported expression" {:form form}))]
            (is (str/includes? rendered "any-source.clj:2:3"))
            (is (str/includes? rendered "^^^^^^ this form could not be emitted"))))))))

(deftest relative-zig-diagnostics-use-the-compilers-working-directory
  (let [source "// Aguafria source: lesson.clj:4:1\n// Aguafria form: 5:3\nmissing();\n"
        root "/tmp/aguafria-diagnostic-fixture/lesson.zig"
        diagnostic (#'runtime/enrich-zig-diagnostic
                    source root {:file "lesson.zig" :line 3 :column 1})]
    (is (= source (:generated-source diagnostic)))
    (is (= {:file "lesson.clj" :line 5 :column 3}
           (:aguafria/source diagnostic)))))

(deftest diagnostic-sources-can-be-classpath-resources
  (is (nil? (#'runtime/source-text "test"))
      "A compiler pseudo-file must not be read as a directory.")
  (is (nil? (#'runtime/source-text "aguafria/zig"))
      "Classpath directories are not diagnostic source files either.")
  (is (str/starts-with? (#'runtime/source-text "aguafria/zig/runtime_test.clj")
                        "(ns aguafria.zig.runtime-test"))
  (is (nil? (#'runtime/clojure-code-frame "missing-diagnostic-source.clj" 3 2 "unavailable"))))

(deftest cyclic-compile-time-identities-follow-source-not-previous-hashes
  (let [module "fixture.cyclic-types"
        describe runtime/declaration-info
        declarations
        (fn [size]
          (mapv describe
                [{:module module :kind :const :name 'capacity
                  :declaration-key [:const 'capacity] :value size}
                 {:module module :kind :const :name 'Packet
                  :declaration-key [:const 'Packet]
                  :value '(make-packet Socket capacity)}
                 {:module module :kind :const :name 'Socket
                  :declaration-key [:const 'Socket]
                  :value '(container {:kind :struct}
                                     [(field-decl :packet [:* Packet])])}]))
        refresh (fn [ds]
                  (with-redefs-fn {#'runtime/registered-declaration-index
                                   (constantly {:by-logical {} :by-module {}})}
                    #(#'runtime/refresh-live-declaration-references ds)))
        original (refresh (declarations 8))
        again (refresh original)
        edited (refresh (declarations 16))
        fingerprint #(mapv :implementation-fingerprint %)]
    (is (= (fingerprint original) (fingerprint again)))
    (is (= (fingerprint original) (fingerprint (mapv describe original))))
    (is (every? false? (map = (fingerprint original) (fingerprint edited)))
        "A changed scalar prerequisite invalidates both members of the recursive type group.")
    (doseq [d again]
      (is (not-any? #(= (:logical-id d) (first %))
                    (:callable-dependency-fingerprints d))))))

(deftest serialized-vector-fingerprints-preserve-ordinary-encoding
  (let [declaration-symbol
        (with-meta 'fixture/Point
          {:zig/name "point"
           :aguafria/zig-reference {:module "fixture" :zig-name "point"
                                    :logical-id "point-id"}})
        inputs [[] [nil] [1 2 3] ["hello ☔" "\"quoted\ntext" :fixture/value]
                [declaration-symbol '(+ 1 2) #{:b :a} {:right [3 4] :left [1 2]}]
                [[:vector [:keyword nil "vector"]] [:map [["key" "value"]]]]]]
    (doseq [input inputs]
      (let [encoded (map #(artifact/print-data
                           (#'runtime/canonical-fingerprint-value %)) input)
            expected (#'runtime/data-fingerprint input)]
        (is (= expected (#'runtime/canonical-vector-fingerprint encoded)))
        (binding [*print-level* 1 *print-length* 1 *print-meta* true
                  *print-namespace-maps* false]
          (is (= expected (#'runtime/canonical-vector-fingerprint encoded))))))))

(deftest cyclic-fingerprints-reuse-shared-source-within-one-calculation
  (let [module "fixture.shared-cyclic-sources"
        declarations
        (fn [capacity]
          (mapv runtime/declaration-info
                [{:module module :kind :const :name 'capacity
                  :declaration-key [:const 'capacity] :value capacity}
                 {:module module :kind :const :name 'Shared
                  :declaration-key [:const 'Shared] :value '[capacity]}
                 {:module module :kind :const :name 'Left
                  :declaration-key [:const 'Left] :value '(make-left Left Shared)}
                 {:module module :kind :const :name 'Right
                  :declaration-key [:const 'Right] :value '(make-right Right Shared)}]))
        scan @#'runtime/nested-form-values
        scans (atom 0)
        calculate (fn [ds]
                    (#'runtime/cyclic-implementation-fingerprints
                     ds {:by-logical {} :by-module {}}))
        initial (declarations 8)
        shared-id (#'runtime/canonical-fingerprint-value
                   (:logical-id (second initial)))
        print-data artifact/print-data
        prints (atom 0)
        fingerprints
        (with-redefs-fn
          {#'runtime/nested-form-values
           (fn [form] (swap! scans inc) (scan form))
           #'artifact/print-data
           (fn [& arguments]
             (let [value (first arguments)]
               (when (and (= 1 (count arguments))
                          (vector? value) (= :vector (first value))
                          (= 2 (count (second value)))
                          (= shared-id (first (second value))))
                 (swap! prints inc))
               (apply print-data arguments)))}
          #(calculate initial))]
    (is (= (count initial) @scans)
        "Each declaration's source is scanned once, including shared prerequisites")
    (is (= 2 (count fingerprints)))
    (is (= 1 @prints) "Shared prerequisites are serialized once across both cycles")
    (is (= fingerprints (calculate (vec (reverse initial))))
        "Component order does not affect artifact identity")
    (is (every? false? (map = (vals fingerprints)
                            (map (calculate (declarations 16)) (keys fingerprints))))
        "A later edit still invalidates both dependent components")))

(deftest unchanged-reference-refresh-reuses-all-declaration-fingerprints
  (let [describe runtime/declaration-info
        calls (atom [])
        refresh (fn [ds]
                  (with-redefs-fn
                    {#'runtime/registered-declaration-index
                     (constantly {:by-logical {} :by-module {}})}
                    #(#'runtime/refresh-live-declaration-references ds)))
        initial (mapv describe
                      [{:module "fixture.refresh" :kind :const :name 'capacity
                        :declaration-key [:const 'capacity] :value 8}
                       {:module "fixture.refresh" :kind :fn :name 'read-capacity
                        :declaration-key [:fn 'read-capacity]
                        :args [] :return :i32 :body '[capacity]}])
        stable (refresh initial)
        counted (fn [ds]
                  (reset! calls [])
                  (with-redefs [runtime/declaration-info
                                (fn [d] (swap! calls conj (:name d)) (describe d))]
                    (refresh ds)))
        unchanged (counted stable)]
    (is (empty? @calls) "Stable declarations are not serialized and hashed again")
    (is (= (binding [*print-meta* true] (pr-str stable))
           (binding [*print-meta* true] (pr-str unchanged))))
    (let [changed (counted (assoc stable 0 (describe (assoc (first stable) :value 16))))]
      (is (some #{'read-capacity} @calls))
      (is (not= (:implementation-fingerprint (second stable))
                (:implementation-fingerprint (second changed))))
      (is (= (refresh (mapv describe
                            (assoc initial 0 (assoc (first initial) :value 16))))
             changed)))
    (let [changed-abi (counted
                       (update stable 1 assoc :abi-type-dependency-fingerprints
                               [[[:external :struct "Changed"] "stale"]]))]
      (is (some #{'read-capacity} @calls)
          "ABI lineage changes must independently invalidate fingerprints")
      (is (= stable changed-abi)))))

(deftest preparation-reference-refresh-reuses-only-identical-snapshots
  (let [index (atom {:by-logical {}})
        calls (atom 0)
        actual-refresh @#'runtime/refresh-live-declaration-references-uncached
        declaration (runtime/declaration-info
                     {:module "fixture.prepare-refresh" :kind :fn :name 'f
                      :declaration-key [:fn 'f] :args [] :return :i32 :body [42]})
        declarations [declaration]]
    (with-redefs-fn
      {#'runtime/preparation-reference-refresh (atom nil)
       #'runtime/registered-declaration-index #(deref index)
       #'runtime/refresh-live-declaration-references-uncached
       (fn [ds targets] (swap! calls inc) (actual-refresh ds targets))}
      (fn []
        (binding [runtime/*compile-only?* true]
          (let [result (#'runtime/refresh-live-declaration-references declarations)]
            (is (identical? result (#'runtime/refresh-live-declaration-references
                                    (vec (seq declarations)))))
            (is (= 1 @calls))
            (is (= (binding [*print-meta* true] (pr-str result))
                   (binding [*print-meta* true]
                     (pr-str (actual-refresh declarations nil))))))
          (#'runtime/refresh-live-declaration-references declarations #{[:fn 'f]})
          (is (= 2 @calls) "Different selected roots are not reused")
          (#'runtime/refresh-live-declaration-references
           [(update declaration :body #(with-meta % {:line 99}))] #{[:fn 'f]})
          (is (= 3 @calls) "Metadata-only changes invalidate too")
          (#'runtime/refresh-live-declaration-references declarations)
          (swap! index assoc :by-logical {"new" declaration})
          (#'runtime/refresh-live-declaration-references declarations)
          (is (= 5 @calls) "Registered dependency changes invalidate")
          (with-redefs-fn {#'runtime/config (atom (assoc @(var-get #'runtime/config)
                                                         :reloadable? false))}
            #(#'runtime/refresh-live-declaration-references declarations))
          (is (= 6 @calls) "Configuration changes invalidate"))
        (#'runtime/refresh-live-declaration-references declarations)
        (#'runtime/refresh-live-declaration-references declarations)
        (is (= 8 @calls) "Runtime refresh is never skipped")))))

(deftest preparation-reference-refresh-does-not-cache-changing-inputs
  (let [index (atom {:by-logical {}})
        calls (atom 0)]
    (with-redefs-fn
      {#'runtime/preparation-reference-refresh (atom nil)
       #'runtime/registered-declaration-index #(deref index)
       #'runtime/refresh-live-declaration-references-uncached
       (fn [ds _]
         (swap! calls inc)
         (swap! index update :by-logical assoc (str @calls) {})
         ds)}
      (fn []
        (binding [runtime/*compile-only?* true]
          (#'runtime/refresh-live-declaration-references [])
          (#'runtime/refresh-live-declaration-references [])
          (is (= 2 @calls))
          (is (nil? @(var-get #'runtime/preparation-reference-refresh))))))))

(deftest slice-storage-uses-the-compilers-pointer-type
  (doseq [slice-type [[:slice :i32]
                      [:slice-const :i32]
                      [:slice [:*const :u8]]]]
    (let [source (#'runtime/emit-jvm-slice-storage-wrapper
                  slice-type (second slice-type) {:slice-set "set_slice"})]
      (is (str/includes? source
                         (str "const __aguafria_items: @TypeOf(@as("
                              (emitter/emit-type slice-type)
                              ", undefined).ptr) = @ptrFromInt(__aguafria_items_address);")))
      (is (not (str/includes? source "const __aguafria_items: [*]"))))))

(deftest inferred-error-results-use-explicit-storage-types
  (let [bridge-type #'runtime/jvm-callable-result-type]
    (doseq [return [:!void [:! :void] [:error-union :void]]]
      (is (= [:error-union :anyerror :void] (bridge-type {:return return}))))
    (is (= [:error-union :anyerror :u32] (bridge-type {:return :!u32})))
    (is (= [:error-union :anyerror :u32]
           (bridge-type {:return :u32 :zig-qualifiers "!"})))
    (is (= :void (bridge-type {:return :void})))
    (is (= [:error-union :MyError :u32]
           (bridge-type {:return [:error-union :MyError :u32]})))))

(deftest live-slice-checks-container-fields-once
  (let [declarations (mapv (fn [n] {:kind :const
                                    :name (symbol (str "item" n))
                                    :declaration-key [:const n]
                                    :logical-id (str n)
                                    :source-order n
                                    :value n})
                           (range 100))
        scans (atom 0)
        original-some clojure.core/some]
    (with-redefs [clojure.core/some
                  (fn [predicate collection]
                    (when (identical? collection declarations) (swap! scans inc))
                    (original-some predicate collection))]
      (is (= declarations (#'runtime/declarations-live-slice declarations declarations))))
    (is (= 1 @scans)))
  (let [declarations [{:kind :field
                       :name 'x
                       :logical-id "x"
                       :declaration-key [:field 'x]
                       :source-order 0
                       :type :i32}
                      {:kind :const
                       :name 'Self
                       :logical-id "Self"
                       :declaration-key [:const 'Self]
                       :source-order 1
                       :value '(aguafria.keyword/This)}
                      {:kind :fn
                       :name 'method
                       :logical-id "method"
                       :declaration-key [:fn 'method]
                       :source-order 2
                       :args []
                       :return :void
                       :body []}
                      {:kind :test
                       :name 'test-only
                       :logical-id "test-only"
                       :declaration-key [:test 'test-only]
                       :source-order 3}]]
    (is (= ['x 'Self 'method]
           (mapv :name (#'runtime/declarations-live-slice declarations [(second declarations)]))))))

(deftest live-slices-use-declaration-keys-for-queue-identity
  (let [guard (reify clojure.lang.IHashEq
                (hasheq [_] (throw (ex-info "Hashed declaration bookkeeping" {}))))
        declarations [{:kind :const :name 'leaf :logical-id ["queue" :const "leaf"]
                       :declaration-key [:const 'leaf] :source-order 0 :value 1
                       :bookkeeping guard}
                      {:kind :const :name 'root :logical-id ["queue" :const "root"]
                       :declaration-key [:const 'root] :source-order 1
                       :value '[leaf leaf leaf root]}]]
    (is (= declarations
           (#'runtime/declarations-live-slice declarations [(second declarations)])))))

(deftest local-type-metadata-participates-in-hot-slices-test
  (doseq [key [:var :zig/type :tag]]
    (let [type-decl (runtime/declaration-info
                     {:module "fixture.metadata" :kind :struct :name 'LocalType :declaration-key [:struct 'LocalType]
                      :fields [{:name :value :type :i32}]})
          caller (runtime/declaration-info
                  {:module "fixture.metadata" :kind :fn :name 'caller :declaration-key [:fn 'caller]
                   :args [] :return :void
                   :body [(list 'let [(with-meta 'local {key 'LocalType :doc 'Ignored}) 'undefined]
                                (list 'set! '_ 'local))]})
          selected ((var-get #'runtime/declarations-live-slice) [type-decl caller] [caller])]
      (is (= #{'LocalType 'caller} (set (map :name selected))) (str key))
      (let [reference (with-meta 'package/RemoteType
                        {:aguafria/zig-reference {:import-alias "package" :import-name "fixture.package"
                                                  :import-namespace 'fixture.package}})
            imports (emitter/declaration-imports
                     [{:body [(list 'let [(with-meta 'local {key reference}) 'undefined] 'local)]}])]
        (is (= 'fixture.package (get-in imports ["package" :namespace])) (str key)))
      (let [reference (with-meta 'external/Type
                        {:aguafria/zig-reference {:kind :import-member :import "external"}})]
        (is (= ["external"]
               ((var-get #'runtime/declaration-named-module-imports)
                [{:body [(with-meta 'local {key reference})]}])))))))

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
      (testing "development libraries retain source locations for native panics"
        (is (= :full (:development-debug-info old-config)))
        (is (= :shared (:development-panic old-config)))
        (is (= []
               ((var-get #'aguafria.zig.runtime/development-compiler-arguments)
                old-config))))
      (testing "stripped libraries remain an explicit option"
        (is (= :none
               (:development-debug-info
                (runtime/configure! {:development-debug-info :none}))))
        (is (= ["-fstrip"]
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

(deftest persistent-native-debug-artifact-test
  (testing "a full-debug macOS cache entry requires its independent DWARF file"
    (with-redefs-fn
      {#'runtime/usable-artifact? #(= "library.dylib" (str %))}
      (fn []
        (is (#'runtime/usable-native-artifact? "library.dylib" nil))
        (is (not (#'runtime/usable-native-artifact? "library.dylib" :macos-flat-dwarf-v1)))))
    (with-redefs-fn
      {#'runtime/usable-artifact? (constantly true)}
      #(is (#'runtime/usable-native-artifact? "library.dylib" :macos-flat-dwarf-v1))))
  (testing "explicitly stripped and non-macOS targets do not request dSYM packaging"
    (is (nil? (#'runtime/native-debug-format {:development-debug-info :none})))
    (is (nil? (#'runtime/native-debug-format {:target "x86_64-linux-gnu"}))))
  (when (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
    (testing "symbolication reads the retained DWARF rather than temporary object references"
      (let [commands (atom [])]
        (with-redefs-fn
          {#'runtime/usable-artifact? (constantly true)
           #'clojure.java.shell/sh
           (fn [& args]
             (swap! commands conj (vec args))
             {:exit 0 :out "callee (in library.dylib) (/source/module.zig:42)\n" :err ""})}
          (fn []
            (let [frames (#'runtime/symbolize-panic-image
                          ["/cache/library.dylib" [{:image-base 4096 :address 4200}]])]
              (is (= "/cache/library.dylib.dwarf" (nth (first @commands) 3)))
              (is (= "/source/module.zig" (:file (first frames))))
              (is (= 42 (:line (first frames)))))))))))

(deftest exact-root-dependency-snapshot-closes-missing-registered-modules-test
  (let [registry (var-get #'aguafria.zig.runtime/registry)
        old-registry @registry
        extend-snapshot
        (var-get
         #'aguafria.zig.runtime/extend-development-dependency-snapshot)
        entries
        {"fixture.parent"
         {:module "fixture.parent"
          :source "const child = @import(\"fixture.child\");"
          :dependencies ["fixture.child" "generated.options"]
          :named-module-imports []
          :dispatch-entries []
          :state-entries []}
         "fixture.child"
         {:module "fixture.child"
          :source "pub const value: u32 = 42;"
          :dependencies []
          :named-module-imports []
          :dispatch-entries []
          :state-entries []}}]
    (try
      (reset! registry
              {"fixture.parent" {:definitions {}}
               "fixture.child" {:definitions {}}})
      (with-redefs-fn
        {#'aguafria.zig.runtime/development-dependency-entry
         (fn [module _ _] (get entries module))}
        (fn []
          (let [snapshot
                (extend-snapshot {} ["fixture.parent"] ["fixture.root"])]
            (is (= #{"fixture.parent" "fixture.child"}
                   (set (keys snapshot))))
            (is (not (contains? snapshot "generated.options"))))))
      (finally
        (reset! registry old-registry)))))

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

(deftest reference-refresh-does-not-resolve-literal-field-names-test
  (let [refresh (var-get #'runtime/refresh-live-declaration-references)
        c-api (runtime/declaration-info
               {:module "fixture.foreign" :kind :const :name 'c-api
                :declaration-key [:const 'c-api]
                :value '(cImport (cInclude "audio.h"))})
        alias (runtime/declaration-info
               {:module "fixture.foreign" :kind :const :name 'ma_sound
                :declaration-key [:const 'ma_sound]
                :value '(field c-api ma_sound)})]
    (with-redefs-fn
      {#'runtime/registered-declaration-index (constantly {:by-logical {} :by-module {}})}
      (fn []
        (let [first-pass (refresh [c-api alias])
              second-pass (refresh first-pass)
              member (last (:value (second first-pass)))]
          (is (nil? (:aguafria/zig-reference (meta member)))
              "A C member name must not acquire a same-named local dependency")
          (is (= (mapv :implementation-fingerprint first-pass)
                 (mapv :implementation-fingerprint second-pass))
              "Unchanged references must reach a stable identity")
          (is (= [(:logical-id c-api)]
                 (mapv first (:callable-dependency-fingerprints
                              (second first-pass))))))))))

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
      {#'aguafria.zig.runtime/registered-declaration-index
       (fn [] {:by-logical {(:logical-id same-named-function) same-named-function}
               :by-module {"fixture.dependency" {:by-name {"dependency" same-named-function}}}})}
      (fn []
        (let [refreshed (first (refresh [alias]))
              reference (:aguafria/zig-reference
                         (meta (:value refreshed)))]
          (is (= :namespace-root (:kind reference)))
          (is (= "fixture.dependency" (:import-name reference)))
          (is (nil? (:logical-id reference))))))))

(deftest first-class-namespace-root-retains-reflectable-container-test
  (let [registry (var-get #'aguafria.zig.runtime/registry)
        reference-index
        (var-get #'aguafria.zig.runtime/declaration-reference-index)
        old-registry @registry
        old-index @reference-index
        module "fixture.reflectable-container"
        root
        (with-meta 'reflectable
          {:aguafria/zig-reference
           {:kind :namespace-root
            :module module
            :import-name module
            :zig-name "reflectable"}})
        info runtime/declaration-info
        self (info {:module module :kind :const :name 'Container
                    :declaration-key [:const 'Container]
                    :value '(ak/This)})
        field (info {:module module :kind :field :name 'value
                     :declaration-key [:field 'value] :type :u32})
        js-api (info {:module module :kind :const :name 'JsApi
                      :declaration-key [:const 'JsApi]
                      :public? true :value :u32})
        unrelated (info {:module module :kind :const :name 'Unused
                         :declaration-key [:const 'Unused]
                         :public? true :value :u64})
        method (info {:module module :kind :fn :name 'private-method
                      :declaration-key [:fn 'private-method]
                      :args [] :return :void :body []})
        zig-test (info {:module module :kind :test :name 'container-test
                        :declaration-key [:test 'container-test] :body []})
        first-class
        (info {:module "fixture.reflecting-consumer"
               :kind :const :name 'Types
               :declaration-key [:const 'Types]
               :value [root]})
        alias (info {:module "fixture.reflecting-consumer"
                     :kind :const :name 'Alias
                     :declaration-key [:const 'Alias]
                     :value root})
        alias-use (info {:module "fixture.reflecting-consumer"
                         :kind :const :name 'AliasedTypes
                         :declaration-key [:const 'AliasedTypes]
                         :value ['Alias]})
        static-member
        (info {:module "fixture.reflecting-consumer"
               :kind :const :name 'Api
               :declaration-key [:const 'Api]
               :value (list 'field root 'JsApi)})
        typed-member (list 'field (list 'aguafria.zig/type root) "JsApi")
        member-reader
        (info {:module "fixture.reflecting-consumer"
               :kind :fn :name 'read-member :jvm-adapter? true
               :declaration-key [:fn 'read-member]
               :args [] :return :usize
               :body [(list '(field __aguafria_jvm :declarationFieldResult)
                            typed-member (list 'aguafria.zig/type root) "JsApi")]})
        definitions
        (fn [declarations]
          (into {} (map (juxt :declaration-key identity)) declarations))]
    (try
      (reset! registry
              {module {:definitions
                       (definitions [self field js-api unrelated method zig-test])}
               "fixture.reflecting-consumer"
               {:definitions (definitions [first-class alias alias-use static-member])}})
      (reset! reference-index
              {:by-module {} :by-logical {} :references {} :revision 0
               :extraction-version
               (var-get
                #'aguafria.zig.runtime/declaration-reference-extraction-version)})
      ((var-get #'aguafria.zig.runtime/registered-declarations-by-logical-id))
      (let [first-class-references
            ((var-get #'aguafria.zig.runtime/declaration-reference-logical-ids)
             first-class)
            static-references
            ((var-get #'aguafria.zig.runtime/declaration-reference-logical-ids)
             static-member)
            retained
            ((var-get #'aguafria.zig.runtime/dependency-live-slice-declarations)
             module first-class-references)]
        (is (contains? first-class-references (:logical-id js-api)))
        (is (not (contains? first-class-references (:logical-id self))))
        (is (contains? static-references (:logical-id js-api)))
        (is (not (contains? static-references (:logical-id self))))
        (is (= #{(:logical-id js-api)}
               (#'runtime/declaration-reference-logical-ids
                (assoc static-member :value typed-member))))
        (is (= #{(:logical-id js-api)}
               (#'runtime/declaration-reference-logical-ids member-reader)))
        (is (= #{(:logical-id js-api) (:logical-id unrelated)}
               (#'runtime/declaration-reference-logical-ids
                (assoc member-reader :jvm-adapter? false))))
        (doseq [member ["Unused" 'dynamic-member '(computed-member)]]
          (is (= #{(:logical-id js-api) (:logical-id unrelated)}
                 (#'runtime/declaration-reference-logical-ids
                  (assoc member-reader
                         :body [(list '(field __aguafria_jvm :declarationFieldResult)
                                      typed-member (list 'aguafria.zig/type root) member)])))
              "Only the exact generated field-reader contract can narrow dependencies"))
        (doseq [writer [:comptimeResult :storageFreeConstantResult]]
          (let [reader (assoc member-reader
                              :body [(list (list 'field '__aguafria_jvm writer) root)])]
            (is (empty? (#'runtime/declaration-reference-logical-ids reader)))
            (is (= #{(:logical-id js-api) (:logical-id unrelated)}
                   (#'runtime/declaration-reference-logical-ids
                    (assoc reader :jvm-adapter? false))))))
        (doseq [member ['JsApi :JsApi "JsApi"]]
          (is (= #{(:logical-id js-api)}
                 (#'runtime/declaration-reference-logical-ids
                  (assoc static-member :value (list 'field root member))))))
        (is (not (contains? (#'runtime/declaration-reference-logical-ids alias)
                            (:logical-id js-api))))
        (is (contains? (#'runtime/declaration-reference-logical-ids alias-use)
                       (:logical-id js-api)))
        (doseq [body [[(list 'set! '_ root)]
                      [(list 'aguafria.keyword/= :_ 'Alias)]]]
          (is (not (contains? (#'runtime/declaration-reference-logical-ids
                               (assoc first-class :kind :comptime
                                      :value nil :body body))
                              (:logical-id js-api)))))
        (is (contains? (#'runtime/declaration-reference-logical-ids
                        (assoc first-class :kind :comptime :value nil
                               :body [(list 'set! '_ (list 'reflect root))]))
                       (:logical-id js-api)))
        (is (= #{'JsApi 'Unused}
               (set (map :name retained)))))
      (finally
        (reset! registry old-registry)
        (reset! reference-index old-index)))))

(deftest nested-string-members-retain-exact-reexported-declarations
  (let [registry (var-get #'runtime/registry)
        reference-index (var-get #'runtime/declaration-reference-index)
        old-registry @registry
        old-index @reference-index
        info runtime/declaration-info
        root (fn [module]
               (with-meta 'dependency
                 {:aguafria/zig-reference {:kind :namespace-root
                                           :module module :import-name module
                                           :zig-name "dependency"}}))
        selected (info {:module "fixture.member-leaf" :kind :const
                        :name 'selected-value :zig-name "selected_value"
                        :declaration-key [:const 'selected-value]
                        :public? true :type :u32 :value 31})
        unrelated (info {:module "fixture.member-leaf" :kind :const
                         :name 'unrelated :declaration-key [:const 'unrelated]
                         :public? true :type :u32 :value 99})
        alias (info {:module "fixture.member-exports" :kind :const
                     :name 'api :declaration-key [:const 'api]
                     :public? true :value (root "fixture.member-leaf")})
        consumer (info {:module "fixture.member-consumer" :kind :fn
                        :name 'read-value :declaration-key [:fn 'read-value]
                        :args [] :return :u32
                        :body [(list 'field
                                     (list 'field (root "fixture.member-exports") "api")
                                     "selected_value")]})
        definitions (fn [declarations]
                      (into {} (map (juxt :declaration-key identity)) declarations))]
    (try
      (reset! registry {"fixture.member-leaf"
                        {:definitions (definitions [selected unrelated])}
                        "fixture.member-exports" {:definitions (definitions [alias])}
                        "fixture.member-consumer" {:definitions (definitions [consumer])}})
      (reset! reference-index {:by-module {} :by-logical {} :references {} :revision 0
                               :extraction-version
                               (var-get #'runtime/declaration-reference-extraction-version)})
      (#'runtime/registered-declarations-by-logical-id)
      (is (= #{(:logical-id selected) (:logical-id alias)}
             (#'runtime/declaration-reference-logical-ids consumer)))
      (finally
        (reset! registry old-registry)
        (reset! reference-index old-index)))))

(deftest reexported-declarations-activate-native-hooks-only-when-used
  (let [info runtime/declaration-info
        module "fixture.reexport-consumer"
        dependency (info {:module "fixture.reexport-dependency"
                          :kind :fn :name 'answer :declaration-key [:fn 'answer]
                          :args [] :return :u32 :body [42]})
        reference (with-meta 'dependency/answer
                    {:aguafria/zig-reference
                     {:kind :fn :module (:module dependency)
                      :logical-id (:logical-id dependency) :zig-name "answer"}})
        alias (info {:module module :kind :const :name 'answer
                     :declaration-key [:const 'answer] :value reference})
        caller (info {:module module :kind :fn :name 'caller
                      :declaration-key [:fn 'caller] :args [] :return :u32
                      :body [(list (with-meta 'answer
                                     {:aguafria/zig-reference
                                      {:logical-id (:logical-id alias)}}))]})]
    (is (empty? (#'runtime/development-linkage-logical-ids [alias])))
    (is (empty? (#'runtime/development-linkage-logical-ids
                 [(assoc alias :value (list reference))])))
    (is (empty? (#'runtime/development-linkage-logical-ids
                 [(assoc caller :kind :test)])))
    (is (contains? (#'runtime/development-linkage-logical-ids [alias caller])
                   (:logical-id dependency)))
    (is (contains? (#'runtime/development-linkage-logical-ids
                    [(assoc alias :value (list reference)) caller])
                   (:logical-id dependency)))))

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
        typed (declaration {:zig/type :i32})
        shorthand (declaration {:var :i32})
        wider-shorthand (declaration {:var :i64})]
    (testing "const/var and local type edits retain the callable ABI"
      (is (= (:abi-fingerprint inferred)
             (:abi-fingerprint mutable)
             (:abi-fingerprint typed)
             (:abi-fingerprint shorthand)
             (:abi-fingerprint wider-shorthand))))
    (testing "metadata read by the emitter changes implementation/source identity"
      (is (not= (:implementation-fingerprint inferred)
                (:implementation-fingerprint mutable)))
      (is (not= (:implementation-fingerprint inferred)
                (:implementation-fingerprint typed)))
      (is (not= (:source-fingerprint inferred)
                (:source-fingerprint mutable)))
      (is (not= (:source-fingerprint inferred)
                (:source-fingerprint typed)))
      (is (not= (:implementation-fingerprint shorthand)
                (:implementation-fingerprint wider-shorthand)))
      (is (not= (:source-fingerprint shorthand)
                (:source-fingerprint wider-shorthand))))))

(deftest linkage-snapshot-cache-bounds-retained-source-and-preserves-keys-test
  (let [cache (var-get #'aguafria.zig.runtime/development-linkage-snapshot-cache)
        original @cache
        computations (atom 0)
        snapshot (fn [source] {"dependency" {:source source :dependencies []
                                             :dispatch-entries [] :state-entries []}})]
    (try
      (reset! cache {})
      (with-redefs-fn
        {#'aguafria.zig.runtime/development-linkage-snapshot-entry-limit 2
         #'aguafria.zig.runtime/development-linkage-snapshot-weight-limit 10
         #'aguafria.zig.runtime/compute-linkable-development-dependency-snapshot
         (fn [dependencies _ _]
           (swap! computations inc)
           dependencies)}
        (fn []
          (let [input (snapshot "first")
                output (#'aguafria.zig.runtime/linkable-development-dependency-snapshot
                        input #{} #{})]
            (is (identical? output
                            (#'aguafria.zig.runtime/linkable-development-dependency-snapshot
                             input #{} #{})))
            (is (= 1 @computations)))
          (#'aguafria.zig.runtime/linkable-development-dependency-snapshot
           (snapshot "other") #{} #{})
          (is (= 2 (count @cache)))
          (#'aguafria.zig.runtime/linkable-development-dependency-snapshot
           (snapshot "third") #{} #{})
          (is (= 1 (count @cache)))
          (is (= 3 @computations))
          (doseq [_ (range 2)]
            (let [oversized (snapshot "more-than-ten")]
              (is (identical? oversized
                              (#'aguafria.zig.runtime/linkable-development-dependency-snapshot
                               oversized #{} #{})))))
          (is (= 5 @computations))
          (is (= 1 (count @cache)))
          (is (= {:entry-count 1 :entry-limit 2 :weight-chars 5 :weight-limit-chars 10}
                 (#'aguafria.zig.runtime/linkage-snapshot-cache-stats)))
          (let [changed (snapshot "new")]
            (is (= changed
                   (#'aguafria.zig.runtime/linkable-development-dependency-snapshot
                    changed #{"updated-reference"} #{"updated-reference"})))
            (is (= 6 @computations)))))
      (finally
        (reset! cache original)))))

(deftest dependency-facade-cache-bounds-source-and-replaces-old-module-states
  (let [cache (var-get #'aguafria.zig.runtime/development-dependency-entry-cache)
        original (java.util.LinkedHashMap. cache)
        put! #'aguafria.zig.runtime/cache-development-dependency-entry!
        get-entry #'aguafria.zig.runtime/cached-development-dependency-entry
        first-state {:revision 1}
        next-state {:revision 2}
        first-entry {:module "owner" :source "first"}
        next-entry {:module "owner" :source "other"}]
    (try
      (.clear cache)
      (with-redefs-fn
        {#'aguafria.zig.runtime/development-dependency-entry-limit 2
         #'aguafria.zig.runtime/development-dependency-entry-weight-limit 10}
        (fn []
          (is (identical? first-entry (put! first-state first-entry)))
          (is (identical? first-entry (get-entry "owner" first-state)))
          (is (nil? (get-entry "owner" (into {} first-state)))
              "Equal maps are not the same immutable-state identity")
          (is (identical? next-entry (put! next-state next-entry)))
          (is (nil? (get-entry "owner" first-state)))
          (is (= 1 (.size cache)))
          (put! {:revision 3} {:module "peer" :source "five!"})
          (is (= 2 (.size cache)))
          (let [entry {:module "third" :source "new"}
                state {:revision 4}]
            (is (identical? entry (put! state entry)))
            (is (= 2 (.size cache)))
            (is (identical? entry (get-entry "third" state))))
          (is (= {:entry-count 2 :entry-limit 2 :weight-chars 8 :weight-limit-chars 10}
                 (#'aguafria.zig.runtime/development-dependency-entry-cache-stats)))
          (let [entry {:module "large" :source "more-than-ten"}
                state {:revision 5}]
            (is (identical? entry (put! state entry)))
            (is (nil? (get-entry "large" state)))
            (is (= 2 (.size cache))))))
      (finally
        (.clear cache)
        (.putAll cache original)))))

(deftest dependency-facade-cache-retains-larger-unchanged-module-graphs
  (let [cache (var-get #'runtime/development-dependency-entry-cache)
        original (java.util.LinkedHashMap. cache)
        emit-module emitter/emit-dependency-module
        emitted (atom 0)
        states (mapv (fn [index]
                       (let [module (str "fixture.large-graph-" index)
                             declaration (#'runtime/declaration-info
                                          {:module module :kind :const :name 'value
                                           :declaration-key [:const 'value]
                                           :type :u32 :value index})]
                         [module {:definitions {[:const 'value] declaration}}]))
                     (range 128))]
    (try
      (.clear cache)
      (with-redefs [emitter/emit-dependency-module
                    (fn [module declarations]
                      (swap! emitted inc)
                      (emit-module module declarations))]
        (let [read-graph #(mapv (fn [[module state]]
                                  (#'runtime/development-dependency-entry
                                   module state (constantly []))) states)
              first-pass (read-graph)
              second-pass (read-graph)]
          (is (= 128 @emitted) "Unchanged dependencies must not be emitted again")
          (is (every? true? (map identical? first-pass second-pass)))
          (is (= 128 (.size cache)))
          (is (<= (:weight-chars (#'runtime/development-dependency-entry-cache-stats))
                  (* 32 1024 1024)))
          (is (every? true?
                      (map #(= (emit-module (first %1)
                                            (vals (:definitions (second %1))))
                               (:source %2))
                           states first-pass)))))
      (finally
        (.clear cache)
        (.putAll cache original)))))

(deftest dependency-facade-cache-retains-recent-states-when-limits-are-reached
  (let [cache (var-get #'aguafria.zig.runtime/development-dependency-entry-cache)
        original (java.util.LinkedHashMap. cache)
        put! #'aguafria.zig.runtime/cache-development-dependency-entry!
        get-entry #'aguafria.zig.runtime/cached-development-dependency-entry
        owner {:revision 1}
        peer {:revision 2}
        third {:revision 3}
        owner-entry {:module "owner" :source "1234"}
        peer-entry {:module "peer" :source "5678"}
        third-entry {:module "third" :source "9012"}]
    (try
      (.clear cache)
      (with-redefs-fn
        {#'aguafria.zig.runtime/development-dependency-entry-limit 2
         #'aguafria.zig.runtime/development-dependency-entry-weight-limit 10}
        (fn []
          (put! owner owner-entry)
          (put! peer peer-entry)
          (is (identical? owner-entry (get-entry "owner" owner)))
          (put! third third-entry)
          (is (identical? owner-entry (get-entry "owner" owner)))
          (is (nil? (get-entry "peer" peer)))
          (is (identical? third-entry (get-entry "third" third)))
          (is (= {:entry-count 2 :entry-limit 2 :weight-chars 8 :weight-limit-chars 10}
                 (#'aguafria.zig.runtime/development-dependency-entry-cache-stats)))
          (let [updated-owner {:revision 4}
                entry {:module "owner" :source "1234567"}]
            (put! updated-owner entry)
            (is (nil? (get-entry "owner" owner)))
            (is (identical? entry (get-entry "owner" updated-owner)))
            (is (nil? (get-entry "third" third)))
            (is (= 1 (.size cache)))
            (is (= 7 (:weight-chars
                      (#'aguafria.zig.runtime/development-dependency-entry-cache-stats))))
            (let [oversized {:module "owner" :source "more-than-ten"}]
              (is (identical? oversized (put! {:revision 5} oversized))))
            (is (nil? (get-entry "owner" updated-owner)))
            (is (zero? (.size cache))))))
      (finally
        (.clear cache)
        (.putAll cache original)))))

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

(deftest module-source-cache-tracks-expanded-callable-aliases-test
  (let [cache (var-get #'aguafria.zig.runtime/module-source-cache)
        empty-cache (var-get #'aguafria.zig.runtime/empty-module-source-cache)
        module-sources (var-get #'aguafria.zig.runtime/module-sources)
        declaration (runtime/declaration-info
                     (assoc function-declaration
                            :args [{:name 'value :type 'NativeResult}]))
        expanded (atom 'NativeResult)]
    (reset! cache empty-cache)
    (try
      (with-redefs-fn
        {#'aguafria.zig.runtime/jvm-wrapper-requests
         (fn [_ request]
           (if (= :jvm-callable-declaration-keys request)
             #{(:declaration-key declaration)} #{}))
         #'aguafria.zig.runtime/bridge-storage-type
         (fn [_ type _] (if (= 'NativeResult type) @expanded type))}
        (fn []
          (let [indirect (module-sources "fixture.live" [declaration])]
            (reset! expanded :i32)
            (let [direct (module-sources "fixture.live" [declaration])
                  repeated (module-sources "fixture.live" [declaration])]
              (is (not= (:compile-source indirect) (:compile-source direct)))
              (is (= :indirect (-> indirect :jvm-callable-specs vals first :mode)))
              (is (= :direct (-> direct :jvm-callable-specs vals first :mode)))
              (is (= direct repeated))
              (is (= 2 (:miss-count @cache)))
              (is (= 1 (:hit-count @cache)))))))
      (finally
        (reset! cache empty-cache)))))

(deftest module-source-cache-tracks-native-type-accessor-identities
  (let [cache (var-get #'runtime/module-source-cache)
        empty-cache (var-get #'runtime/empty-module-source-cache)
        module "fixture.finalized-type"
        declaration (emitter/prepare-declaration
                     *ns* {:module module :kind :const :name 'Tag
                           :declaration-key [:const 'Tag]
                           :value (emitter/enum-container-form {:type :u8} [:ready])})
        finalized (assoc declaration :schema-fingerprint "final-schema")]
    (reset! cache empty-cache)
    (try
      (with-redefs-fn
        {#'runtime/jvm-wrapper-requests
         (fn [_ request]
           (if (= :jvm-type-declaration-keys request) #{[:const 'Tag]} #{}))}
        (fn []
          (let [initial (#'runtime/module-sources module [declaration])
                current (#'runtime/module-sources module [finalized])
                repeated (#'runtime/module-sources module [finalized])]
            (is (= (:source-fingerprint declaration) (:source-fingerprint finalized)))
            (is (not= (:compile-source initial) (:compile-source current)))
            (is (str/includes? (:compile-source current)
                               (get-in current [:jvm-type-specs
                                                (symbol module "Tag") :size-getter])))
            (is (= current repeated))
            (is (= 2 (:miss-count @cache)))
            (is (= 1 (:hit-count @cache))))))
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

(deftest enum-backing-type-participates-in-schema-identity
  (let [declaration {:module "fixture.live"
                     :kind :const
                     :name 'Tag
                     :declaration-key [:const 'Tag]
                     :value '(aguafria.zig/container {:kind :enum :type :u8}
                                                     [(aguafria.zig/enum-field-decl :ready {})])}
        baseline (runtime/declaration-info declaration)
        changed (runtime/declaration-info
                 (assoc-in declaration [:value] (list 'aguafria.zig/container
                                                      {:kind :enum :type :u32}
                                                      (nth (:value declaration) 2))))]
    (is (not= (:schema-fingerprint baseline) (:schema-fingerprint changed)))))

(deftest converted-container-schema-ignores-method-bodies-test
  (let [declaration
        {:module "fixture.live"
         :kind :const
         :name 'Options
         :declaration-key [:const 'Options]
         :value
         '(aguafria.zig/container
           {:kind :struct :layout :normal}
           [(aguafria.zig/field-decl count :u32)
            (aguafria.zig/fn-decl calculate :u32  [] 1)])}
        baseline (runtime/declaration-info declaration)
        body-change
        (runtime/declaration-info
         (assoc declaration :value
                '(aguafria.zig/container
                  {:kind :struct :layout :normal}
                  [(aguafria.zig/field-decl count :u32)
                   (aguafria.zig/fn-decl calculate :u32  [] 2)])))
        layout-change
        (runtime/declaration-info
         (assoc declaration :value
                '(aguafria.zig/container
                  {:kind :struct :layout :packed}
                  [(aguafria.zig/field-decl count :u32)
                   (aguafria.zig/fn-decl calculate :u32  [] 1)])))]
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
            [(aguafria.zig/field-decl count :u32)
             (aguafria.zig/fn-decl calculate :u32  [] 1)])]}
        baseline (runtime/declaration-info declaration)
        method-change
        (runtime/declaration-info
         (assoc declaration :body
                '[(aguafria.zig/container
                   {:kind :struct :layout :normal}
                   [(aguafria.zig/field-decl count :u32)
                    (aguafria.zig/fn-decl calculate :u32  [] 2)])]))
        field-change
        (runtime/declaration-info
         (assoc declaration :body
                '[(aguafria.zig/container
                   {:kind :struct :layout :normal}
                   [(aguafria.zig/field-decl count :u64)
                    (aguafria.zig/fn-decl calculate :u32  [] 1)])]))]
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
                                 [(list 'field-decl 'value field-type)]))})]
               {:declaration declaration
                :reference (runtime/declaration-reference declaration)})))
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
          :body '[(container {:kind :struct :layout :normal} [])]})
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
         [[["fixture.reexport.consumer" :fn "start"] "abi" "impl"]])
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
           #{{:kind :type :logical-id (:logical-id factory)}}))
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
