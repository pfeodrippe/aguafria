(ns aguafria.zig.discovery-import-identity-test
  (:require [aguafria.keyword]
            [aguafria.std.mem]
            [aguafria.zig]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.explain :as explain]
            [aguafria.zig.jvm]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(deftest identity-refinement-uses-isolated-compiler-observations
  (let [queries (atom [])
        operation {:id "0" :status :unobserved}
        incomplete #{[[:* nil]]}
        complete #{[[:* :i32]]}]
    (with-redefs-fn
      {#'runtime/call-with-inspection-context (fn [_ f] (f))
       #'runtime/inspect-module! (fn [& _] {:exit 0 :err ""})
       #'discovery/local-type-identities! (fn [_] {:identities #{} :query {}})
       #'discovery/observed-type-identities (constantly #{})
       #'discovery/inspect-operations!
       (fn [_ selected]
         (swap! queries conj selected)
         (if selected
           {:err "" :observed {"0" complete}}
           {:err "error: rejected probe" :operations [operation] :observed {}}))
       #'discovery/isolate-probes!
       (fn [& _] {:observed {"0" incomplete} :failures {} :attempts 3})}
      (fn []
        (let [report (discovery/analyze! 'example)]
          (is (= [nil #{"0"}] @queries))
          (is (= (vec complete) (get-in report [:operations 0 :signatures])))
          (is (= :observed (get-in report [:operations 0 :status])))
          (is (= 4 (:inspection-attempts report)))
          (is (false? (get-in report [:identity-refinement :compiler-errors?]))))))))

(deftest contextual-conversions-prepare-the-ordinary-jvm-adapter
  (let [context (create-ns (symbol (str "aguafria.context-plan-" (random-uuid))))]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (eval '(a/defn narrow :u8 [[value :u16]]
                 (k/as (k/intCast value) :u8)))
        (eval '(a/defn to-float :f32 [[value :u16]]
                 (k/as (k/floatFromInt value) :f32)))
        (eval '(a/defn cast-pointer [:*const :u32] [[ptr [:*const [:array 1 :u32]]]]
                 (k/as (k/ptrCast (k/alignCast ptr)) [:*const :u32]))))
      (let [fail! (fn [& _] (throw (ex-info "Native invocation during preparation" {})))
            report (binding [runtime/*compile-only?* true]
                     (with-redefs [runtime/invoke! fail!]
                       (discovery/prepare! (ns-name context))))
            conversions (filter :contextual-plan (:operations report))]
        (is (zero? (get-in report [:baseline :exit])) (get-in report [:baseline :diagnostics]))
        (is (empty? (:probe-failures report)) (:probe-failures report))
        (is (= 3 (count conversions)))
        (is (= #{[[:u8 :u16]] [[:f32 :u16]] [[[:*const :u32] [:*const [:array 1 :u32]]]]}
               (set (map :signatures conversions))))
        (is (every? #(= :prepared (:status %)) (mapcat :handlers conversions))
            (pr-str (mapcat :handlers conversions)))
        (with-open [number (aguafria.keyword/u16 7)
                    array (aguafria.zig/array [1234] :u32)
                    pointer (aguafria.keyword/& array)]
          (let [events (atom [])]
            (binding [explain/*reporter* #(swap! events conj %)]
              (with-open [narrow (aguafria.keyword/as (aguafria.keyword/intCast number) :u8)
                          floating (aguafria.keyword/as (aguafria.keyword/floatFromInt number) :f32)
                          cast (aguafria.keyword/as
                                (aguafria.keyword/ptrCast (aguafria.keyword/alignCast pointer))
                                [:*const :u32])]
                (is (= 7 (aguafria.zig/value narrow)))
                (is (= 7.0 (aguafria.zig/value floating)))
                (is (= [:*const :u32] (value/qualified-type cast)))))
            (is (empty? (filter #(= :compiled (:event %)) @events)) (pr-str @events)))))
      (finally
        (remove-ns (ns-name context))))))

(deftest contextual-plans-preserve-literal-operands
  (let [context (create-ns (symbol (str "aguafria.context-literal-" (random-uuid))))]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (eval '(a/defstruct Point [[:x :u32]]))
        (eval '(a/defn parent [:* Point] [[x [:* :u32]]]
                 (k/as (k/fieldParentPtr "x" x) [:* Point])))
        (eval '(a/defn fixed-pointer [:* :u32] []
                 (k/as (k/ptrFromInt 4096) [:* :u32]))))
      (let [report (binding [runtime/*compile-only?* true]
                     (with-redefs [runtime/invoke!
                                   (fn [& _] (throw (ex-info "Native invocation during preparation" {})))]
                       (discovery/prepare! (ns-name context))))
            conversions (filter :contextual-plan (:operations report))
            Point (var-get (ns-resolve context 'Point))]
        (is (zero? (get-in report [:baseline :exit])))
        (is (empty? (:probe-failures report)))
        (is (= 2 (count conversions)))
        (is (= #{"x" 4096}
               (set (keep :literal (mapcat #(tree-seq coll? seq (:contextual-plan %)) conversions)))))
        (is (every? #(= :prepared (:status %)) (mapcat :handlers conversions))
            (pr-str (mapcat :handlers conversions)))
        (with-open [point (aguafria.keyword/var (Point {:x 7}))
                    pointer (aguafria.keyword/& (:x point))]
          (let [events (atom [])]
            (binding [explain/*reporter* #(swap! events conj %)]
              (with-open [parent (aguafria.keyword/as
                                  (aguafria.keyword/fieldParentPtr "x" pointer) [:* Point])
                          fixed (aguafria.keyword/as (aguafria.keyword/ptrFromInt 4096) [:* :u32])]
                (is (= (.address (value/segment point))
                       (value/pointer-address (aguafria.zig/value parent))))
                (is (= 4096 (value/pointer-address (aguafria.zig/value fixed))))))
            (is (empty? (filter #(= :compiled (:event %)) @events)) (pr-str @events)))))
      (finally (remove-ns (ns-name context))))))

(deftest extern-calls-are-observed-and-prepared-without-invocation
  (let [context (create-ns (symbol (str "aguafria.extern-probe-" (random-uuid))))]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (eval '(a/defextern abs :c_int {:zig/prefix "extern \"c\""} [[n :c_int]]))
        (eval '(a/defn magnitude :c_int [[n :i64]] (abs (k/intCast n)))))
      (let [fail! (fn [& _] (throw (ex-info "Native invocation during preparation" {})))
            report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                     (discovery/prepare! (ns-name context)))
            operation (first (filter #(= "abs" (name (:function %))) (:operations report)))]
        (is (zero? (get-in report [:baseline :exit])))
        (is (empty? (:probe-failures report)))
        (is (= :observed (:status operation)))
        (is (= '[[{:contextual-argument
                  [:c_int {:contextual-call [aguafria.keyword/intCast [:i64]]}]}]]
               (:signatures operation)))
        (is (= [:prepared] (mapv :status (:handlers operation))))
        (is (= 42 (aguafria.zig/value ((ns-resolve context 'abs) -42))))
        (is (= 43 (aguafria.zig/value ((ns-resolve context 'magnitude) -43))))
        (let [source (aguafria.keyword/i64 -44)
              events (atom [])]
          (binding [explain/*reporter* #(swap! events conj %)]
            (is (= 44 (aguafria.zig/value
                       ((ns-resolve context 'abs) (aguafria.keyword/intCast source))))))
          (is (empty? (filter #(= :compiled (:event %)) @events)) (pr-str @events))))
      (finally (remove-ns (ns-name context))))))

(deftest compiler-discovery-preserves-function-and-signature-type-identities
  (let [provider (create-ns (symbol (str "aguafria.signature-provider-" (random-uuid))))
        consumer (create-ns (symbol (str "aguafria.signature-consumer-" (random-uuid))))]
    (try
      (doseq [n [provider consumer]]
        (binding [*ns* n]
          (refer 'clojure.core)
          (alias 'a 'aguafria.zig)
          (alias 'k 'aguafria.keyword)))
      (binding [*ns* provider runtime/*source-only-registration?* true]
        (eval '(a/defconst Opaque (a/opaque [])))
        (eval '(a/defn open-handle [:optional [:* Opaque]] [] nil)))
      (binding [*ns* consumer runtime/*source-only-registration?* true]
        (alias 'provider (ns-name provider))
        (eval '(a/defn callback :i32 [[n :i32]] (k/+ n 1)))
        (eval '(a/defn call-callback :i32 [[f [:*const (k/TypeOf callback)]]]
                 (f 41)))
        (eval '(a/defn run :i32 [] (call-callback (k/& callback))))
        (eval '(a/defn absent? :bool [] (k/== (provider/open-handle) nil))))
      (let [fail! (fn [& _] (throw (ex-info "Native invocation during preparation" {})))
            report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                     (discovery/prepare! (ns-name consumer)))
            operations (filter #(contains? #{'aguafria.keyword/& 'aguafria.keyword/==}
                                           (:function %)) (:operations report))]
        (is (zero? (get-in report [:baseline :exit])))
        (is (empty? (:probe-failures report)))
        (is (= 2 (count operations)))
        (doseq [operation operations]
          (is (= :observed (:status operation)))
          (is (not-any? nil? (tree-seq coll? seq (:signatures operation)))
              (pr-str (select-keys operation [:form :signatures])))
          (is (every? #(= :prepared (:status %)) (:handlers operation))
              (pr-str (:handlers operation))))
        (is (= 42 (aguafria.zig/value ((ns-resolve consumer 'run)))))
        (is (true? ((ns-resolve consumer 'absent?))))
        (let [events (atom [])]
          (binding [explain/*reporter* #(swap! events conj %)]
            (let [pointer (aguafria.keyword/& (var-get (ns-resolve consumer 'callback)))]
              (is (aguafria.zig.value/zig-value? pointer))))
          (is (empty? (filter #(= :compiled (:event %)) @events))
              (pr-str @events))))
      (finally
        (remove-ns (ns-name consumer))
        (remove-ns (ns-name provider))))))

(deftest compiler-equivalent-function-pointer-schemas-are-callable
  (let [context (create-ns (symbol (str "aguafria.pointer-schema-" (random-uuid))))]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (eval '(a/defn callback :i32 [[n :i32]] (k/+ n 1)))
        (eval '(a/defn incompatible :u32 [[n :u32]] n))
        (eval '(a/defn call-callback :i32 [[f [:*const (k/TypeOf callback)]]]
                 (f 41)))
        (eval '(a/defn run :i32 [] (call-callback (k/& callback)))))
      (binding [runtime/*compile-only?* true]
        (discovery/prepare! (ns-name context)))
      (with-open [pointer (aguafria.keyword/& (var-get (ns-resolve context 'callback)))
                  wrong (aguafria.keyword/& (var-get (ns-resolve context 'incompatible)))]
        (let [call (ns-resolve context 'call-callback)]
          ;; Compile-only comparison also accepts expression-shaped types.
          ;; Operation discovery currently prepares the call, not this pair of
          ;; JVM representations of its parameter type.
          (binding [runtime/*compile-only?* true]
            (aguafria.zig.jvm/precompile-type-equivalence!
             (str (ns-name context))
             (get-in (meta call) [:aguafria/declaration :args 0 :type])
             (value/qualified-type pointer)))
          (let [events (atom [])]
            (binding [explain/*reporter* #(swap! events conj %)]
              (is (= 42 (aguafria.zig/value (call pointer)))))
            (is (empty? (filter #(= :compiled (:event %)) @events)) (pr-str @events)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"wrong Zig type" (call wrong)))
          (let [events (atom [])]
            (binding [explain/*reporter* #(swap! events conj %)]
              (is (= 42 (aguafria.zig/value (call pointer)))))
            (is (empty? (filter #(= :compiled (:event %)) @events)) (pr-str @events)))))
      (finally (remove-ns (ns-name context))))))

(deftest computed-type-undefined-construction-is-prepared
  (let [context (create-ns (symbol (str "aguafria.undefined-constructor-" (random-uuid))))]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (eval '(a/defn Box :type [[T {:attrs #{k/comptime}} :type]]
                 (a/struct [[:value T]])))
        (eval '(a/defn construct (Box :u32) []
                 (k/as k/undefined (Box :u32)))))
      (let [report (binding [runtime/*compile-only?* true]
                     (with-redefs [runtime/invoke!
                                   (fn [& _] (throw (ex-info "Executed a body" {})))]
                       (discovery/prepare! (ns-name context))))
            constructor (first (filter #(= 'aguafria.keyword/as (:function %))
                                       (:operations report)))]
        (is (zero? (get-in report [:baseline :exit])))
        (is (= 'aguafria.keyword/undefined (:constructor-value constructor)))
        (is (= [:prepared] (mapv :status (:handlers constructor)))
            (pr-str (:handlers constructor)))
        (let [type ((ns-resolve context 'Box) :u32)
              events (atom [])]
          (binding [explain/*reporter* #(swap! events conj %)]
            ;; Do not read undefined storage; verifying allocation is sufficient.
            (with-open [instance (aguafria.keyword/as aguafria.keyword/undefined type)]
              (is (value/zig-value? instance))))
          (is (empty? (filter #(= :compiled (:event %)) @events)) (pr-str @events))))
      (finally (remove-ns (ns-name context))))))

(deftest compiler-discovery-preserves-opaque-children-of-imported-aliases
  (let [provider (create-ns (symbol (str "aguafria.opaque-provider-" (random-uuid))))
        consumer (create-ns (symbol (str "aguafria.opaque-consumer-" (random-uuid))))]
    (try
      (doseq [n [provider consumer]]
        (binding [*ns* n]
          (refer 'clojure.core)
          (alias 'a 'aguafria.zig)))
      (binding [*ns* provider runtime/*source-only-registration?* true]
        (eval '(a/defconst Opaque (a/opaque [])))
        (eval '(a/defconst Handle (a/type [:optional [:* Opaque]]))))
      (binding [*ns* consumer runtime/*source-only-registration?* true]
        (alias 'provider (ns-name provider))
        (alias 'mem 'aguafria.std.mem)
        (eval '(a/defn handles [:array 2 provider/Handle] []
                 (mem/zeroes [:array 2 provider/Handle]))))
      (let [fail! (fn [& _] (throw (ex-info "Native invocation during preparation" {})))
            report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                     (discovery/prepare! (ns-name consumer)))
            operations (filter #(= 'aguafria.std.mem/zeroes (:function %))
                               (:operations report))]
        (is (zero? (get-in report [:baseline :exit])))
        (is (empty? (:probe-failures report)))
        (is (= 1 (count operations)))
        (is (not-any? nil? (tree-seq coll? seq (:signatures (first operations))))
            (pr-str (:signatures (first operations))))
        (is (= [:prepared] (mapv :status (mapcat :handlers operations)))
            (pr-str (mapcat :handlers operations)))
        (is (= [nil nil] (aguafria.zig/value ((ns-resolve consumer 'handles))))))
      (finally
        (remove-ns (ns-name consumer))
        (remove-ns (ns-name provider))))))

(deftest inspection-reuses-dependencies-only-within-one-analysis
  (let [provider (create-ns (symbol (str "aguafria.inspection-provider-" (random-uuid))))
        consumer (create-ns (symbol (str "aguafria.inspection-consumer-" (random-uuid))))
        snapshot @#'runtime/static-dependency-snapshot
        snapshots (atom [])
        observe (fn [report]
                  (select-keys report [:operations :type-identities :compiler-errors?]))]
    (try
      (doseq [n [provider consumer]]
        (binding [*ns* n]
          (refer 'clojure.core)
          (alias 'a 'aguafria.zig)
          (alias 'k 'aguafria.keyword)))
      (binding [*ns* provider runtime/*source-only-registration?* true]
        (eval '(a/defconst amount :i32 1)))
      (binding [*ns* consumer runtime/*source-only-registration?* true]
        (alias 'provider (ns-name provider))
        (eval '(a/defn value :i32 [] (k/+ provider/amount 41))))
      (with-redefs-fn
        {#'runtime/static-dependency-snapshot
         (fn [& args]
           (let [result (apply snapshot args)]
             (swap! snapshots conj result)
             result))}
        (fn []
          (let [cached (discovery/analyze! (ns-name consumer))
                first-snapshot (first @snapshots)]
            (is (= 1 (count @snapshots)))
            (is (zero? (get-in cached [:baseline :exit])))
            (reset! snapshots [])
            (let [uncached (with-redefs [runtime/call-with-inspection-context
                                         (fn [_ f] (f))]
                             (discovery/analyze! (ns-name consumer)))]
              (is (< 1 (count @snapshots)))
              (is (= (observe cached) (observe uncached))))
            (binding [*ns* provider runtime/*source-only-registration?* true]
              (eval '(a/defconst amount :i32 2)))
            (reset! snapshots [])
            (let [updated (discovery/analyze! (ns-name consumer))]
              (is (= 1 (count @snapshots)))
              (is (not= first-snapshot (first @snapshots)))
              (is (zero? (get-in updated [:baseline :exit])))))))
      (finally
        (remove-ns (ns-name consumer))
        (remove-ns (ns-name provider))))))

(deftest imported-identities-keep-their-module-qualification
  (let [provider (create-ns (symbol (str "aguafria.identity-provider-" (random-uuid))))
        consumer (create-ns (symbol (str "aguafria.identity-consumer-" (random-uuid))))]
    (try
      (doseq [n [provider consumer]]
        (binding [*ns* n]
          (refer 'clojure.core)
          (alias 'a 'aguafria.zig)
          (alias 'k 'aguafria.keyword)))
      (binding [*ns* provider runtime/*source-only-registration?* true]
        (eval '(a/defstruct Device [[:number :i32]]))
        (eval '(a/defvar count :i32 7)))
      (binding [*ns* consumer runtime/*source-only-registration?* true]
        (alias 'provider (ns-name provider))
        (eval '(a/defn read-device :i32 [[device provider/Device]]
                 (k/+ (:number device) provider/count))))
      (let [report (discovery/analyze! (ns-name consumer))]
        (is (zero? (get-in report [:baseline :exit])) (get-in report [:baseline :diagnostics]))
        (is (false? (:compiler-errors? report)) (:diagnostics report))
        (is (seq (:operations report)))
        (is (every? #(= :observed (:status %)) (:operations report))))
      (finally
        (remove-ns (ns-name consumer))
        (remove-ns (ns-name provider))))))

(deftest inspection-preserves-root-module-c-include-options
  (let [context (create-ns (symbol (str "aguafria.include-probe-" (random-uuid))))
        configuration (runtime/configuration)]
    (try
      (runtime/configure!
       {:module-zig-args
        (assoc (:module-zig-args configuration) (str (ns-name context))
               [(str "-I" (.getAbsolutePath (io/file "test/aguafria/zig/fixtures")))])})
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (eval '(a/defconst api (k/cImport (k/cInclude "inspection_options.h"))))
        (eval '(a/defn answer :i32 [] (:AGUAFRIA_INSPECTION_VALUE api))))
      (let [report (discovery/analyze! (ns-name context))]
        (is (zero? (get-in report [:baseline :exit])) (get-in report [:baseline :diagnostics]))
        (is (false? (:compiler-errors? report)) (:diagnostics report)))
      (finally
        (runtime/configure! configuration)
        (remove-ns (ns-name context))))))

(deftest typed-c-constants-keep-their-compiler-known-value
  (let [context (create-ns (symbol (str "aguafria.c-constant-" (random-uuid))))
        configuration (runtime/configuration)]
    (try
      (runtime/configure!
       {:module-zig-args
        (assoc (:module-zig-args configuration) (str (ns-name context))
               [(str "-I" (.getAbsolutePath (io/file "test/aguafria/zig/fixtures")))])})
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (eval '(a/defconst api (k/cImport (k/cInclude "inspection_options.h"))))
        (eval '(a/defn read-format :c_uint []
                 ((:inspection_format_value api) (:INSPECTION_FORMAT api))))
        (eval '(a/defn read-selected :c_uint [[flag :bool]]
                 ((:inspection_format_value api)
                  (if flag (:INSPECTION_FORMAT api) (:INSPECTION_ALTERNATE_FORMAT api)))))
        (eval '(a/defn read-mutable :c_uint []
                 ((:inspection_format_value api) (:inspection_mutable_format api)))))
      (let [report (binding [runtime/*compile-only?* true]
                     (with-redefs [runtime/invoke!
                                   (fn [& _] (throw (ex-info "Executed a body" {})))]
                       (discovery/prepare! (ns-name context))))
            calls (filter :method-call? (:operations report))
            constant-call (first (filter #(= 'read-format (:declaration-name %)) calls))
            selected-call (first (filter #(= 'read-selected (:declaration-name %)) calls))
            mutable-call (first (filter #(= 'read-mutable (:declaration-name %)) calls))
            events (atom [])
            api (var-get (ns-resolve context 'api))]
        (value/value api)
        (is (zero? (get-in report [:baseline :exit])) (get-in report [:baseline :diagnostics]))
        (is (empty? (:probe-failures report)) (:probe-failures report))
        (is (= 3 (count calls)))
        (is (seq (get-in constant-call [:signatures 0 2 :comptime-expression]))
            (pr-str constant-call))
        (is (= :c_uint (get-in mutable-call [:signatures 0 2])) (pr-str mutable-call))
        (doseq [call calls]
          (is (every? #(= :prepared (:status %)) (:handlers call)) (pr-str (:handlers call))))
        (is (= 2 (count (:handlers selected-call))) (pr-str selected-call))
        (binding [explain/*reporter* #(swap! events conj %)]
          (doseq [flag [true false]]
            (with-open [result ((:inspection_format_value api)
                                (if flag (:INSPECTION_FORMAT api) (:INSPECTION_ALTERNATE_FORMAT api)))]
              (is (= (if flag 7 11) (aguafria.zig/value result)))))
          (with-open [result ((:inspection_format_value api) (:INSPECTION_FORMAT api))]
            (is (= 7 (aguafria.zig/value result))))
          (with-open [field (:inspection_mutable_format api)]
            (is (= :native (:representation (value/realize! field))))
            (with-open [result ((:inspection_format_value api) (:inspection_mutable_format api))]
              (is (= 8 (aguafria.zig/value result))))))
        (is (empty? (filter #(= :compiled (:event %)) @events)) (pr-str @events)))
      (finally
        (runtime/configure! configuration)
        (remove-ns (ns-name context))))))

(deftest function-valued-constants-prepare-and-call-without-reading-function-storage
  (let [context (create-ns (symbol (str "aguafria.function-alias-" (random-uuid))))
        configuration (runtime/configuration)]
    (try
      (runtime/configure!
       {:module-zig-args
        (assoc (:module-zig-args configuration) (str (ns-name context))
               [(str "-I" (.getAbsolutePath (io/file "test/aguafria/zig/fixtures")))])})
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (eval '(a/defconst api (k/cImport (k/cInclude "inspection_options.h"))))
        (eval '(a/defconst read-format (:inspection_format_value api)))
        (eval '(a/defn- answer :u32 [] 42))
        (eval '(a/defconst read-answer answer))
        (eval '(a/defn call-format :c_uint [[format :c_uint]] (read-format format)))
        (eval '(a/defn call-literal :c_uint [] (read-format 9)))
        (eval '(a/defn call-answer :u32 [] (read-answer))))
      (let [report (binding [runtime/*compile-only?* true]
                     (with-redefs [runtime/invoke!
                                   (fn [& _] (throw (ex-info "Executed a body" {})))]
                       (discovery/prepare! (ns-name context))))
            calls (filter #(#{"read-format" "read-answer"} (some-> % :function name))
                          (:operations report))
            read-format (var-get (ns-resolve context 'read-format))
            read-answer (var-get (ns-resolve context 'read-answer))
            events (atom [])]
        (is (zero? (get-in report [:baseline :exit])) (get-in report [:baseline :diagnostics]))
        (is (empty? (:probe-failures report)) (:probe-failures report))
        (is (= 3 (count calls)))
        (doseq [call calls]
          (is (= :observed (:status call)) (pr-str call))
          (is (and (seq (:handlers call))
                   (every? #(= :prepared (:status %)) (:handlers call))) (pr-str call)))
        (with-open [format (aguafria.keyword/as 7 :c_uint)]
          (binding [explain/*reporter* #(swap! events conj %)]
            (with-open [result (read-format format)]
              (is (= 7 (aguafria.zig/value result))))
            (with-open [result (apply read-format [9])]
              (is (= 9 (aguafria.zig/value result))))
            (with-open [result (read-answer)]
              (is (= 42 (aguafria.zig/value result))))))
        (is (= :pending (:status (value/info read-format))))
        (is (= :pending (:status (value/info read-answer))))
        (is (empty? (filter #(= :compiled (:event %)) @events)) (pr-str @events)))
      (finally
        (runtime/configure! configuration)
        (remove-ns (ns-name context))))))

(deftest composite-input-probes-preserve-runtime-call-representations
  (let [context (create-ns (symbol (str "aguafria.composite-input-" (random-uuid))))
        configuration (runtime/configuration)]
    (try
      (runtime/configure!
       {:module-zig-args
        (assoc (:module-zig-args configuration) (str (ns-name context))
               [(str "-I" (.getAbsolutePath (io/file "test/aguafria/zig/fixtures")))])})
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (eval '(a/defconst api (k/cImport (k/cInclude "inspection_options.h"))))
        (eval '(a/defstruct Pair [[:x :i64] [:enabled :bool]]))
        (eval '(a/defn make-pair Pair [[x :i64]]
                 (k/as {:x x :enabled (k/!= x 0)} Pair)))
        (eval '(a/defn branch :f32 [[choice :i32]]
                 (k/as (if (k/== choice 0) 0.5 (if (k/== choice 1) 0.75 0.0)) :f32)))
        (eval '(a/defn width :usize [[choice :bool]] (k/as (if choice 4 1) :usize)))
        (eval '(a/defn store :void [[ptr [:* :u32]] [number :f64]]
                 (k/atomicStore :u32 ptr (k/intFromFloat number) :.release)))
        (eval '(a/defn convert :c_uint [[number :i64]]
                 ((:inspection_format_value api) (k/intCast number)))))
      (let [report (binding [runtime/*compile-only?* true]
                     (with-redefs [runtime/invoke!
                                   (fn [& _] (throw (ex-info "Executed a body" {})))]
                       (discovery/prepare! (ns-name context))))
            Pair (var-get (ns-resolve context 'Pair))
            api (var-get (ns-resolve context 'api))
            calls (filter #(or (= 'aguafria.keyword/as (:function %))
                                (= 'aguafria.keyword/atomicStore (:function %))
                                (and (:method-call? %)
                                     (= :inspection_format_value (:member %))))
                          (:operations report))
            events (atom [])]
        (is (zero? (get-in report [:baseline :exit])) (get-in report [:baseline :diagnostics]))
        (is (empty? (:probe-failures report)) (:probe-failures report))
        (is (= 5 (count calls)))
        (doseq [call calls]
          (is (= :observed (:status call)) (pr-str call))
          (is (and (seq (:handlers call))
                   (every? #(= :prepared (:status %)) (:handlers call))) (pr-str call)))
        (with-open [number (aguafria.keyword/i64 7)
                    floating (aguafria.keyword/f64 23.5)
                    cell (aguafria.keyword/var 0 :u32)
                    ptr (aguafria.keyword/& cell)]
          (binding [explain/*reporter* #(swap! events conj %)]
            (doseq [flag [false true]]
              (with-open [pair (aguafria.keyword/as {:x number :enabled flag} Pair)]
                (is (= {:x 7 :enabled flag} (aguafria.zig/value pair)))))
            (doseq [literal [0.5 0.75 0.0]]
              (with-open [result (aguafria.keyword/as literal :f32)]
                (is (= literal (double (aguafria.zig/value result))))))
            (doseq [literal [4 1]]
              (with-open [result (aguafria.keyword/as literal :usize)]
                (is (= literal (aguafria.zig/value result)))))
            (aguafria.keyword/atomicStore :u32 ptr (aguafria.keyword/intFromFloat floating) :.release)
            (is (= 23 (aguafria.zig/value cell)))
            (with-open [result ((:inspection_format_value api) (aguafria.keyword/intCast number))]
              (is (= 7 (aguafria.zig/value result))))))
        (is (empty? (filter #(= :compiled (:event %)) @events)) (pr-str @events)))
      (finally
        (runtime/configure! configuration)
        (remove-ns (ns-name context))))))

(deftest member-calls-use-the-compilers-concrete-parameter-types
  (let [context (create-ns (symbol (str "aguafria.member-parameter-" (random-uuid))))
        configuration (runtime/configuration)]
    (try
      (runtime/configure!
        {:module-zig-args
         (assoc (:module-zig-args configuration) (str (ns-name context))
                [(str "-I" (.getAbsolutePath (io/file "test/aguafria/zig/fixtures")))])})
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (eval '(a/defconst api (k/cImport (k/cInclude "inspection_options.h"))))
        (eval '(a/defn combine :c_uint []
                 (let [options (k/| (:INSPECTION_FORMAT api) (:INSPECTION_ALTERNATE_FORMAT api))]
                   ((:inspection_format_value api) options)))))
      (let [report (binding [runtime/*compile-only?* true]
                     (with-redefs [runtime/invoke!
                                   (fn [& _] (throw (ex-info "Executed a body" {})))]
                       (discovery/prepare! (ns-name context))))
            call (first (filter #(and (:method-call? %)
                                     (= :inspection_format_value (:member %))) (:operations report)))
            api (var-get (ns-resolve context 'api))
            events (atom [])]
        (is (zero? (get-in report [:baseline :exit])))
        (is (= :observed (:status call)))
        (is (every? #(= :prepared (:status %)) (:handlers call)) (pr-str call))
        (with-open [options (aguafria.keyword/| (:INSPECTION_FORMAT api) (:INSPECTION_ALTERNATE_FORMAT api))]
          (binding [explain/*reporter* #(swap! events conj %)]
            (with-open [result ((:inspection_format_value api) options)]
              (is (= 15 (aguafria.zig/value result)))))
          (is (empty? (filter #(= :compiled (:event %)) @events)) (pr-str @events))))
      (finally
        (runtime/configure! configuration)
        (remove-ns (ns-name context))))))

(deftest reflected-member-scalar-conversion-is-checked
  (let [context (create-ns (symbol (str "aguafria.member-scalars-" (random-uuid))))]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (eval '(a/defstruct CastTargets
                 [(a/fn integer :u8 [[value :u8]] value)
                  (a/fn real :f32 [[value :f32]] value)
                  (a/fn generic :i64 [[value :anytype]] value)])))
      (let [target (var-get (ns-resolve context 'CastTargets))]
        (with-open [input (aguafria.keyword/i64 7)
                    integer ((:integer target) input)
                    real ((:real target) input)
                    generic ((:generic target) input)]
          (is (= 7 (aguafria.zig/value integer)))
          (is (= 7.0 (double (aguafria.zig/value real))))
          (is (= 7 (aguafria.zig/value generic))))
        (with-open [input (aguafria.keyword/f64 1.5)
                    result ((:real target) input)]
          (is (= 1.5 (double (aguafria.zig/value result)))))
        (doseq [number [-1 256]]
          (with-open [input (aguafria.keyword/i64 number)]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"out of range"
                                 ((:integer target) input)))))
        (with-open [input (aguafria.keyword/i64 9)
                    result ((:integer target) input)]
          (is (= 9 (aguafria.zig/value result)) "A subsequent valid call still succeeds")))
      (finally (remove-ns (ns-name context))))))

(deftest error-set-members-do-not-use-container-declaration-reflection
  (let [context (create-ns (symbol (str "aguafria.error-member-" (random-uuid))))]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (eval '(a/defconst Errors (a/type [:error-set [:Failure]])))
        (eval '(a/defn same :bool []
                 (k/== (:Failure Errors) (:Failure Errors)))))
      (let [report (binding [runtime/*compile-only?* true]
                     (with-redefs [runtime/invoke!
                                   (fn [& _] (throw (ex-info "Executed a body" {})))]
                       (discovery/prepare! (ns-name context))))
            Errors (var-get (ns-resolve context 'Errors))]
        (is (zero? (get-in report [:baseline :exit])))
        (is (empty? (:probe-failures report)))
        (is (value/zig-error? (:Failure Errors)))
        (is (true? (aguafria.keyword/== (:Failure Errors) (:Failure Errors)))))
      (finally (remove-ns (ns-name context))))))

(deftest local-c-import-and-computed-type-identities-come-from-zig
  (let [context (create-ns (symbol (str "aguafria.local-c-types-" (random-uuid))))
        configuration (runtime/configuration)]
    (try
      (runtime/configure!
       {:module-zig-args
        (assoc (:module-zig-args configuration) (str (ns-name context))
               [(str "-I" (.getAbsolutePath (io/file "test/aguafria/zig/fixtures")))])})
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (eval '(a/defconst api (k/cImport (k/cInclude "inspection_options.h"))))
        (eval '(a/defconst Point (:InspectionPoint api)))
        (eval '(a/defconst Size (:InspectionSize api)))
        (eval '(a/defconst number :i32 42))
        (eval '(a/defn make-point Point [] (a/init {:x 3 :y 4} Point)))
        (eval '(a/defn make-size Size [] (a/init {:width 5} Size)))
        (eval '(a/defn read-size :c_uint [[size Size]] (:width size)))
        (eval '(a/defn read-point :c_int [[point Point]] (:x point)))
        (eval '(a/defn callback-address (k/TypeOf (k/& read-point)) []
                 (k/& read-point))))
      (let [report (binding [runtime/*compile-only?* true]
                     (with-redefs [runtime/invoke!
                                   (fn [& _] (throw (ex-info "Executed a body" {})))]
                       (discovery/prepare! (ns-name context))))
            fields (filter :member (:operations report))
            addresses (filter #(= 'aguafria.keyword/& (:function %)) (:operations report))
            events (atom [])]
        (is (zero? (get-in report [:baseline :exit])) (get-in report [:baseline :diagnostics]))
        (is (= '#{api Point Size} (:local-type-identities report)))
        (is (false? (get-in report [:local-type-query :compiler-errors?])))
        (is (empty? (:probe-failures report)))
        (is (= 4 (count fields)))
        (doseq [operation fields]
          (is (not-any? nil? (tree-seq coll? seq (:signatures operation)))
              (pr-str (select-keys operation [:form :signatures])))
          (is (= [:prepared] (mapv :status (:handlers operation)))
              (pr-str (:handlers operation))))
        (is (seq addresses))
        (doseq [operation addresses]
          (is (not-any? nil? (tree-seq coll? seq (:signatures operation))))
          (is (= [:prepared] (mapv :status (:handlers operation)))
              (pr-str (:handlers operation))))
        (binding [explain/*reporter* #(swap! events conj %)]
          (with-open [point ((ns-resolve context 'make-point))
                      result ((ns-resolve context 'read-point) point)]
            (is (= 3 (aguafria.zig/value result))))
          (with-open [size ((ns-resolve context 'make-size))
                      result ((ns-resolve context 'read-size) size)]
            (is (= 5 (aguafria.zig/value result)))))
        (is (empty? (filter #(= :compiled (:event %)) @events)) (pr-str @events)))
      (finally
        (runtime/configure! configuration)
        (remove-ns (ns-name context))))))

(deftest explicitly-contextualized-nested-casts-have-an-ordinary-operand-type
  (let [context (create-ns (symbol (str "aguafria.nested-cast-" (random-uuid))))]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (eval '(a/defn position :f32 [[page :u32]]
                 (k/f32 (k/- 217.0 (k/* (k/as (k/floatFromInt page) :f32) 208.0))))))
      (let [report (binding [runtime/*compile-only?* true]
                     (with-redefs [runtime/invoke!
                                   (fn [& _] (throw (ex-info "Executed a body" {})))]
                       (discovery/prepare! (ns-name context))))
            operation (first (filter #(= 'aguafria.keyword/f32 (:function %))
                                     (:operations report)))]
        (is (zero? (get-in report [:baseline :exit])))
        (is (empty? (:probe-failures report)))
        (is (some? operation))
        (is (not (:contextual-input? operation)))
        (is (= [[:f32 :f32]] (:signatures operation)))
        (is (= [:prepared] (mapv :status (:handlers operation)))))
      (is (= 9.0 (double (aguafria.zig/value ((ns-resolve context 'position) 1)))))
      (finally (remove-ns (ns-name context))))))

(deftest callback-alias-arguments-use-the-callees-type-scope
  (let [provider (create-ns (symbol (str "aguafria.callback-provider-" (random-uuid))))
        consumer (create-ns (symbol (str "aguafria.callback-consumer-" (random-uuid))))]
    (try
      (doseq [n [provider consumer]]
        (binding [*ns* n]
          (refer 'clojure.core)
          (alias 'a 'aguafria.zig)))
      (binding [*ns* provider runtime/*source-only-registration?* true]
        (eval '(a/defstruct Point {:layout :extern} [[:x :i32]])))
      (binding [*ns* consumer runtime/*source-only-registration?* true]
        (alias 'provider (ns-name provider))
        (eval '(a/defconst Callback
                 (a/type [:*const [:fn {:callconv :.c}
                                   [{:name :point :type [:*const provider/Point]}] :i32]])))
        (eval '(a/defn read-point :i32 {:zig/qualifiers "callconv(.c)"}
                 [[point [:*const provider/Point]]]
                 (:x @point)))
        (eval '(a/defn invoke-callback :i32 [[callback Callback]]
                 (let [point (provider/Point {:x 42})]
                   (callback (aguafria.keyword/& point)))))
        (eval '(a/defn ignore-nested :i32 [[callbacks [:slice [:optional Callback]]]]
                 (aguafria.keyword/= :_ callbacks)
                 7)))
      (doseq [function ['invoke-callback 'ignore-nested]]
        (is (= :prepared
               (:status (binding [runtime/*compile-only?* true]
                          (with-redefs [runtime/invoke!
                                        (fn [& _] (throw (ex-info "Executed a body" {})))]
                            (runtime/precompile-function! (ns-resolve consumer function))))))))
      (is (= 42 (aguafria.zig/value
                 ((ns-resolve consumer 'invoke-callback)
                  (aguafria.keyword/& (var-get (ns-resolve consumer 'read-point)))))))
      (is (= 7 (aguafria.zig/value ((ns-resolve consumer 'ignore-nested) [nil]))))
      (finally
        (remove-ns (ns-name consumer))
        (remove-ns (ns-name provider))))))

(deftest cast-argument-probes-use-the-zig-callee-parameter-type
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-typed-context-fixture :reload))
  (let [context (the-ns 'aguafria.zig.discovery-typed-context-fixture)]
    (try
      (let [report (binding [runtime/*compile-only?* true]
                     (with-redefs [runtime/invoke!
                                   (fn [& _] (throw (ex-info "Executed a body" {})))]
                       (discovery/prepare! (ns-name context))))
            operation (first (filter #(= "take-index" (name (:function %)))
                                     (:operations report)))]
        (is (zero? (get-in report [:baseline :exit])))
        (is (empty? (:probe-failures report)))
        (is (= :observed (:status operation)))
        (is (= '[[{:contextual-argument
                  [:usize {:contextual-call [aguafria.keyword/intCast [:u32]]}]}]]
               (:signatures operation)))
        (is (= [:prepared] (mapv :status (:handlers operation)))))
      (let [source (aguafria.keyword/u32 37)
            array (aguafria.zig/array [12 23] :u8)
            pointer (aguafria.keyword/& array)
            events (atom [])]
        (binding [explain/*reporter* #(swap! events conj %)]
          (is (= 37 (aguafria.zig/value
                     ((ns-resolve context 'take-index) (aguafria.keyword/intCast source)))))
          (is (= 37.0 (double (aguafria.zig/value
                               ((ns-resolve context 'take-float)
                                (aguafria.keyword/floatFromInt source))))))
          (let [returned ((ns-resolve context 'take-pointer) (aguafria.keyword/ptrCast pointer))]
            (is (= (value/pointer-address (value/decoded pointer))
                   (value/pointer-address (value/decoded returned)))))
          (let [results (atom [])]
            (is (= 37 (runtime/invoke-with-result!
                        (ns-resolve context 'take-index)
                        [(aguafria.keyword/intCast source)]
                        (fn [result _] (swap! results conj result) result))))
            (is (= [37] @results))))
        (is (empty? (filter #(= :compiled (:event %)) @events)) (pr-str @events)))
      (finally
        (remove-ns (ns-name context))))))

(deftest imported-result-aliases-stay-qualified-in-jvm-bridges
  (let [provider (create-ns (symbol (str "aguafria.alias-provider-" (random-uuid))))
        consumer (create-ns (symbol (str "aguafria.alias-consumer-" (random-uuid))))]
    (try
      (doseq [n [provider consumer]]
        (binding [*ns* n]
          (refer 'clojure.core)
          (alias 'a 'aguafria.zig)))
      (binding [*ns* provider runtime/*source-only-registration?* true]
        (eval '(a/defstruct NativeRecord [[:number :i32]]))
        (eval '(a/defconst Record NativeRecord))
        (eval '(a/defconst Handle (a/type [:optional [:* NativeRecord]])))
        (eval '(a/defconst Opaque (a/opaque [])))
        (eval '(a/defconst OpaquePointer (a/type [:optional [:* Opaque]])))
        (eval '(a/defconst OpaqueHandle OpaquePointer)))
      (binding [*ns* consumer runtime/*source-only-registration?* true]
        (alias 'provider (ns-name provider))
        (eval '(a/defn make-record provider/Record []
                 (provider/Record {:number 42})))
        (eval '(a/defn absent-handle provider/Handle [] nil))
        (eval '(a/defn absent-opaque-handle provider/OpaqueHandle [] nil)))
      (is (= :prepared (:status (runtime/precompile-function!
                                 (ns-resolve consumer 'make-record)))))
      (is (= {:number 42}
             (aguafria.zig/value ((ns-resolve consumer 'make-record)))))
      (is (= :prepared (:status (runtime/precompile-function!
                                 (ns-resolve consumer 'absent-handle)))))
      (is (nil? (aguafria.zig/value ((ns-resolve consumer 'absent-handle)))))
      (is (= :prepared (:status (runtime/precompile-function!
                                 (ns-resolve consumer 'absent-opaque-handle)))))
      (is (nil? (aguafria.zig/value ((ns-resolve consumer 'absent-opaque-handle)))))
      (finally
        (remove-ns (ns-name consumer))
        (remove-ns (ns-name provider))))))

(deftest imported-argument-aliases-use-their-native-storage-types
  (let [provider (create-ns (symbol (str "aguafria.argument-provider-" (random-uuid))))
        consumer (create-ns (symbol (str "aguafria.argument-consumer-" (random-uuid))))]
    (try
      (doseq [n [provider consumer]]
        (binding [*ns* n]
          (refer 'clojure.core)
          (alias 'a 'aguafria.zig)))
      (binding [*ns* provider runtime/*source-only-registration?* true]
        (eval '(a/defconst NativeResult (a/type :c_int)))
        (eval '(a/defconst Result NativeResult))
        (eval '(a/defconst Flag (a/type :bool)))
        (eval '(a/defconst Amount (a/type :f64)))
        (eval '(a/defconst Text (a/type [:slice-const :u8])))
        (eval '(a/defstruct Record [[:number :i32]]))
        (eval '(a/defconst Handle (a/type [:optional [:* Record]]))))
      (binding [*ns* consumer runtime/*source-only-registration?* true]
        (alias 'provider (ns-name provider))
        (eval '(a/defn result :c_int [[value provider/Result]] value))
        (eval '(a/defn flag :bool [[value provider/Flag]] value))
        (eval '(a/defn amount :f64 [[value provider/Amount]] value))
        (eval '(a/defn text-length :usize [[value provider/Text]] (:len value)))
        (eval '(a/defn handle provider/Handle [[value provider/Handle]] value)))
      (doseq [name '[result flag amount text-length handle]]
        (is (= :prepared (:status (runtime/precompile-function! (ns-resolve consumer name))))))
      (let [events (atom [])]
        (binding [explain/*reporter* #(swap! events conj %)]
          (doseq [number [0 -1000001004 1000001003 -1]]
            (is (= number (aguafria.zig/value ((ns-resolve consumer 'result) number)))))
          (doseq [flag [true false]]
            (is (= flag (aguafria.zig/value ((ns-resolve consumer 'flag) flag)))))
          (is (= 1.25 (aguafria.zig/value ((ns-resolve consumer 'amount) 1.25))))
          (is (= 5 (aguafria.zig/value ((ns-resolve consumer 'text-length) "hello"))))
          (is (nil? (aguafria.zig/value ((ns-resolve consumer 'handle) nil)))))
        (is (empty? (filter #(= :compiled (:event %)) @events)) (pr-str @events)))
      (finally
        (remove-ns (ns-name consumer))
        (remove-ns (ns-name provider))))))

(deftest inspection-preserves-assignment-and-call-result-context
  (let [context (create-ns (symbol (str "aguafria.branch-probe-" (random-uuid))))]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (eval '(a/defconst channel-count :c_int 16))
        (eval '(a/defn take-count :u32 [[n :u32]] n))
        (eval '(a/defn branch-count :u32 [[flag :bool]]
                 (let [n (k/var 0 :u32)]
                   (k/= n channel-count)
                   (k/= n (if flag 16 2))
                   (k/+ n (take-count (if flag 4 0)))))))
      (let [report (discovery/analyze! (ns-name context))
            calls (filter #(= "take-count" (name (:function %))) (:operations report))
            assignments (filter :assignment (:operations report))]
        (is (zero? (get-in report [:baseline :exit])))
        (is (empty? (:probe-failures report)))
        (is (= [[:u32]] (:signatures (first calls))))
        (is (= 2 (count (set (map :form assignments)))))
        (is (= #{[[:u32 :u32]]} (set (map :signatures assignments))))
        (let [prepared (binding [runtime/*compile-only?* true]
                         (discovery/prepare! (ns-name context)))
              assignments (filter :assignment (:operations prepared))]
          (is (= 2 (count (set (map :form assignments)))))
          (is (every? #(= [:prepared] (mapv :status (:handlers %))) assignments)
              (pr-str assignments)))
        (is (= 20 (aguafria.zig/value ((ns-resolve context 'branch-count) true))))
        (is (= 2 (aguafria.zig/value ((ns-resolve context 'branch-count) false)))))
      (finally
        (remove-ns (ns-name context))))))

(deftest contextual-assignment-preparation-matches-ordinary-jvm-assignment
  (let [context (create-ns (symbol (str "aguafria.assignment-context-" (random-uuid))))]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (eval '(a/defn assign-integer :u32 [[input :u64]]
                 (let [target (k/var 0 :u32)]
                   (k/= target (k/intCast input))
                   target)))
        (eval '(a/defn assign-float :i32 [[input :f64]]
                 (let [target (k/var 0 :i32)]
                   (k/= target (k/intFromFloat input))
                   target)))
        (eval '(a/defn add-integer :u32 [[input :u64]]
                 (let [target (k/var 0 :u32)]
                   (k/+= target (k/intCast input))
                   target))))
      (let [report (binding [runtime/*compile-only?* true]
                     (with-redefs [runtime/invoke!
                                   (fn [& _] (throw (ex-info "Executed a body" {})))]
                       (discovery/prepare! (ns-name context))))
            assignments (filter :assignment (:operations report))]
        (is (zero? (get-in report [:baseline :exit])) (get-in report [:baseline :diagnostics]))
        (is (empty? (:probe-failures report)))
        (is (= 3 (count (set (map :form assignments)))))
        (doseq [operation assignments]
          (is (every? :contextual-call (map second (:signatures operation)))
              (pr-str (:signatures operation)))
          (is (= [:prepared] (mapv :status (:handlers operation)))
              (pr-str (:handlers operation)))))
      (let [integer (aguafria.keyword/u64 37)
            floating (aguafria.keyword/f64 12.5)
            target (aguafria.keyword/var 0 :u32)
            float-target (aguafria.keyword/var 0 :i32)
            events (atom [])]
        (binding [explain/*reporter* #(swap! events conj %)]
          (aguafria.keyword/= target (aguafria.keyword/intCast integer))
          (is (= 37 (aguafria.zig/value target)))
          (aguafria.keyword/+= target (aguafria.keyword/intCast integer))
          (is (= 74 (aguafria.zig/value target)))
          (aguafria.keyword/= float-target (aguafria.keyword/intFromFloat floating))
          (is (= 12 (aguafria.zig/value float-target))))
        (is (empty? (filter #(= :compiled (:event %)) @events)) (pr-str @events)))
      (finally
        (remove-ns (ns-name context))))))

(deftest contextual-index-preparation-matches-ordinary-jvm-indexing
  (let [context (create-ns (symbol (str "aguafria.index-context-" (random-uuid))))
        schemas [[:array 4 :i32] [:slice-const :i32] [:many-const :i32]
                 [:array 4 {:sentinel 0} :i32]]]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (doseq [[index schema] (map-indexed vector schemas)]
          (eval (list 'a/defn (symbol (str "at-" index)) :i32
                      [['items schema] ['index :i32]]
                      '(a/get items (k/intCast index)))))
        (eval '(a/defn float-index :i32 [[items [:slice-const :i32]] [index :f64]]
                 (a/get items (k/intFromFloat index)))))
      (let [report (binding [runtime/*compile-only?* true]
                     (with-redefs [runtime/invoke!
                                   (fn [& _] (throw (ex-info "Executed a body" {})))]
                       (discovery/prepare! (ns-name context))))
            indices (filter #(= :index (:storage-kind %)) (:operations report))]
        (is (zero? (get-in report [:baseline :exit])) (get-in report [:baseline :diagnostics]))
        (is (empty? (:probe-failures report)))
        (is (= 5 (count indices)))
        (doseq [operation indices]
          (is (= :observed (:status operation)) (pr-str operation))
          (is (every? :contextual-call (map last (:signatures operation)))
              (pr-str (:signatures operation)))
          (is (= [:prepared] (mapv :status (:handlers operation)))
              (pr-str (:handlers operation)))))
      (let [array (aguafria.zig/array [10 20 30 40] :i32)
            receivers [array
                       (aguafria.keyword/as (aguafria.keyword/& array) [:slice-const :i32])
                       (aguafria.keyword/as (aguafria.keyword/& array) [:many-const :i32])
                       (aguafria.zig/array [10 20 30 40] {:sentinel 0} :i32)]
            integers (mapv aguafria.keyword/i32 [1 2])
            floating (aguafria.keyword/f64 2.0)
            events (atom [])]
        (binding [explain/*reporter* #(swap! events conj %)]
          (doseq [receiver receivers
                  [index expected] (map vector integers [20 30])]
            (is (= expected (aguafria.zig/value
                             (aguafria.zig/get receiver (aguafria.keyword/intCast index))))))
          (is (= 30 (aguafria.zig/value
                     (aguafria.zig/get (second receivers) (aguafria.keyword/intFromFloat floating))))))
        (is (empty? (filter #(= :compiled (:event %)) @events)) (pr-str @events)))
      (finally
        (remove-ns (ns-name context))))))
