(ns aguafria.zig.jvm-scoped-statement-test
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest comptime-only-captures-do-not-register-runtime-adapters
  (let [fail! (fn [& _] (throw (ex-info "Requested a comptime-only runtime adapter" {})))]
    (with-redefs-fn
      {#'jvm/precompile-inputs fail!
       #'jvm/prepared-call-inputs fail!
       #'jvm/prepare-scoped-plan! fail!
       #'runtime/precompile-function! fail!}
      #(doseq [type [:comptime_int :comptime_float :type :null :undefined]
               address [[:* type] [:* {:const? false} type]]]
         (let [signature {:caller (ns-name *ns*) :form '(a/while-loop {} ready)
                          :captures ['state] :types [{:literal 0 :type type} address]
                          :result? false}
               result (jvm/precompile-scoped! signature)]
           (is (= (assoc signature :status :unsupported :reason :comptime-only-mutation)
                  result)))))))

(deftest mutable-captures-use-the-compiler-observed-storage-type
  (let [plans (atom [])
        inputs (atom [])
        compiled (atom [])]
    (with-redefs-fn
      {#'jvm/prepared-call-inputs
       (fn [types declarations]
         (swap! inputs conj [types declarations])
         {:parameters [] :expression-arguments [] :operands []})
       #'jvm/prepare-scoped-plan!
       (fn [_ _ entries _ _ _]
         (swap! plans conj entries)
         {:context *ns* :function 'fixture/scoped})
       #'runtime/precompile-function! #(swap! compiled conj %)}
      #(doseq [[value-type address expected-type mutable?]
               [[{:literal 0 :type :comptime_int} [:* :u8] :u8 true]
                [{:literal true :type :bool} [:* {:const? false} :bool] :bool true]
                [:i32 [:* :i32] :i32 true]
                [{:literal 0 :type :comptime_int} [:*const :comptime_int]
                 {:literal 0 :type :comptime_int} false]
                [{:literal 0 :type :comptime_int} [:* {:const? true} :comptime_int]
                 {:literal 0 :type :comptime_int} false]]]
         (is (= :prepared
                (:status (jvm/precompile-scoped!
                          {:caller (ns-name *ns*) :form '(a/while-loop {} ready)
                           :captures ['state] :types [value-type address] :result? false}))))
         (is (= [{:name 'state :type expected-type :mutable? mutable? :comptime? false}]
                (last @plans)))
         (is (= [[(if mutable? :usize value-type)]
                 [(if mutable? {:type :usize}
                      {:type :anytype :properties {:jvm/literal? true}})]]
                (last @inputs)))))
    (is (= (repeat 5 'fixture/scoped) @compiled))))

(deftest scoped-forms-preserve-native-result-roles
  (let [context (the-ns 'aguafria.zig.jvm-scoped-statement-test)]
    (doseq [[form result?]
            [['(a/switch input (case [0] 42) (case-else 7)) true]
             ['(a/switch-stmt input (case [0] (k/+= state 1))) false]
             ['(a/if-capture-stmt {:payload [item]} input (k/= state item)) false]
             ['(a/labeled-switch vm input (case [0] (k/continue vm 1))) true]
             ['(a/labeled-switch-stmt vm input (case [0] (k/continue vm 1))) false]
             ['(a/while-loop {} ready (k/+= state 1)) false]
             ['(a/while-loop {:else-expression false} ready (k/break true)) true]
             ['(k/for [item items] (k/= :_ item)) false]
             ['(k/for [item items] (k/= :_ item) (a/else-expression 12)) true]
             ['(a/inline-for [item items] (k/= :_ item)) false]
             ['(a/for-loop {:label outer} [item items] (a/break-label outer)) false]
             ['(a/for-loop {} [item items] (k/= :_ item) (a/else-expression 12)) true]
             ['(k/while ready (k/continue)) false]
             ['(k/while ready (k/continue) (a/else-expression 12)) true]]]
      (is (= result? (emitter/scoped-result? context form)))
      (let [expanded (binding [*ns* context] (macroexpand-1 form))]
        (is (= result? (last expanded)))
        (is (= (list 'quote form) (nth expanded 2)))))))

(deftest scoped-macros-do-not-change-native-switch-or-loop-source
  (let [context (the-ns 'aguafria.zig.jvm-scoped-statement-test)]
    (doseq [[emit plain namespaced]
            [[emitter/emit-stmt-in
              '(switch-stmt input (case [0] (set! state 1)))
              '(a/switch-stmt input (case [0] (set! state 1)))]
             [emitter/emit-expr
              '(while-loop {:else-expression false} ready (break true))
              '(a/while-loop {:else-expression false} ready (break true))]
             [emitter/emit-stmt-in
              '(inline-for [item items] (set! state item))
              '(a/inline-for [item items] (set! state item))]
             [emitter/emit-stmt-in
              '(for-loop {:label outer} [item items] (break-label outer))
              '(a/for-loop {:label outer} [item items] (break-label outer))]]]
      (is (= (emit context plain) (emit context namespaced))))
    (is (= #{'source 'state}
           (set (emitter/scoped-captures
                 context
                 '(a/switch-stmt source
                                 (case [:.ok] [state] (k/= :_ state))
                                 (case [:.other] (k/+= state 1)))
                 '[source state]))))))

(deftest native-dispatch-labels-are-not-jvm-captures
  (let [context (the-ns 'aguafria.zig.jvm-scoped-statement-test)]
    (is (= #{'code 'ip 'result}
           (set (emitter/scoped-captures
                 context
                 '(a/labeled-switch vm code
                                    (case [:.add] (continue vm (a/get code ip)))
                                    (case [:.end] result))
                 '[vm code ip result]))))))

(deftest native-for-owners-capture-outer-values-not-iterators-or-labels
  (let [context (the-ns 'aguafria.zig.jvm-scoped-statement-test)]
    (doseq [form ['(a/inline-for [item items] (k/+= total item))
                  '(a/for-loop {:label outer} [item items]
                               (k/for [nested items]
                                 (k/+= total nested)
                                 (k/continue outer)))]]
      (is (= #{'items 'total}
             (set (emitter/scoped-captures context form '[outer item nested items total])))))))

(deftest for-labels-preserve-same-named-lexical-values
  (let [context (the-ns 'aguafria.zig.jvm-scoped-statement-test)
        form '(a/for-loop {:label outer :body-label inner} [item items]
                          (k/+= total outer)
                          (k/continue outer))
        qualified (binding [emitter/*local-type-bindings* {'outer false 'inner false}
                            emitter/*local-name-bindings* {'outer '(deref address)
                                                           'inner 'some-value}]
                    (emitter/qualify-form context form))]
    (is (= #{'items 'total 'outer}
           (set (emitter/scoped-captures context form '[outer inner item items total]))))
    (is (= {:label 'outer :body-label 'inner} (second qualified)))
    (is (= '(aguafria.keyword/+= total (deref address)) (nth qualified 3)))
    (is (= '(continue outer) (nth qualified 4)))))

(deftest labels-do-not-hide-same-named-value-captures
  (let [context (the-ns 'aguafria.zig.jvm-scoped-statement-test)]
    (is (= ['vm]
           (emitter/scoped-captures
            context '(a/labeled-switch vm input (case [0] vm)) '[vm])))
    (is (= ['ready]
           (emitter/scoped-captures
            context '(a/while-loop {:label loop} ready (continue loop)) '[loop ready])))))

(deftest qualification-preserves-labels-and-error-capture-scope
  (let [context (the-ns 'aguafria.zig.jvm-scoped-statement-test)
        qualified (binding [emitter/*local-name-bindings* {'vm '(deref address)
                                                           'err 'outer-error}
                            emitter/*local-type-bindings* {'vm false 'err false}]
                    (emitter/qualify-form
                     context
                     '(a/labeled-switch vm input
                                        (case [0] (k/continue vm vm))
                                        (case [1] (a/break-label vm))
                                        (case-else vm))))
        loop-form '(a/while-loop {:label vm :body-label vm :error [err]
                                  :else-expression err} input (k/break 0))
        qualified-loop (binding [emitter/*local-name-bindings* {'vm '(deref address)
                                                                'err 'outer-error}
                                 emitter/*local-type-bindings* {'vm false 'err false}]
                         (emitter/qualify-form context loop-form))]
    (is (= 'vm (second qualified)))
    (is (= '(continue vm (deref address)) (last (nth qualified 3))))
    (is (= '(break-label vm) (last (nth qualified 4))))
    (is (= '(deref address) (last (last qualified))))
    (is (= 'vm (:label (second qualified-loop))))
    (is (= 'vm (:body-label (second qualified-loop))))
    (is (= 'err (:else-expression (second qualified-loop))))
    (is (= '(continue) (emitter/qualify-form context '(k/continue))))
    (is (= [] (emitter/scoped-captures context loop-form '[vm err])))))

(deftest contextual-control-results-defer-to-the-zig-destination
  (let [context (the-ns 'aguafria.zig.jvm-scoped-statement-test)]
    (doseq [form ['(a/switch input (case [0] (k/intCast wide))
                             (case-else (k/intCast wide)))
                  '(a/labeled-switch vm input (case [0] (k/intCast wide))
                                     (case-else (k/intCast wide)))
                  '(a/while-loop {:else-expression (k/intCast wide)} ready (k/break 0))
                  '(a/while-loop {:else-expression 0} ready (k/break (k/intCast wide)))]]
      (is (emitter/scoped-result-context-required? context form)))
    (is (not (emitter/scoped-result-context-required?
              context '(a/switch input (case [0] (k/u8 wide)) (case-else 0)))))
    (is (not (emitter/scoped-result-context-required?
              context '(a/while-loop {:else-expression 0} ready
                                     (a/while-loop {} nested (k/break (k/intCast wide)))
                                     (k/break 1)))))))

(deftest statement-try-errors-cannot-be-discarded-by-an-ordinary-let
  (let [calls (atom 0)
        result (atom {:error {:name "TestExpectedEqual"}})]
    (with-redefs-fn
      {#'jvm/prepare-scoped-plan!
       (fn [& _] {:context *ns* :function 'fixture/statement :parameters []
                  :propagates-errors? true})
       #'runtime/invoke! (fn [& _] (swap! calls inc) @result)}
      #(do
         (is (thrown-with-msg?
              clojure.lang.ExceptionInfo #"TestExpectedEqual"
              (let [_ (jvm/invoke-scoped! (ns-name *ns*) '(a/switch-stmt 0) {} false)]
                :unreachable)))
         (is (= 1 @calls))
         (reset! result {:ok nil})
         (is (nil? (jvm/invoke-scoped! (ns-name *ns*) '(a/switch-stmt 0) {} false)))
         (is (= 2 @calls))))))

(deftest scoped-results-remove-only-the-generated-error-carrier
  (let [result (atom {:ok 42})]
    (with-redefs-fn
      {#'jvm/prepare-scoped-plan!
       (fn [& _] {:context *ns* :function 'fixture/result :parameters []
                  :expression 42 :propagates-errors? true})
       #'jvm/invoke-expression! (fn [& _] @result)}
      #(do
         (is (= 42 (jvm/invoke-scoped! (ns-name *ns*) '(a/with-block :done) {} true)))
         (reset! result {:error {:name "Rejected"}})
         (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Rejected"
                               (jvm/invoke-scoped! (ns-name *ns*) '(a/with-block :done) {} true)))
         (reset! result {:ok {:error {:name "OriginalResult"}}})
         (is (= {:error {:name "OriginalResult"}}
                (jvm/invoke-scoped! (ns-name *ns*) '(a/with-block :done) {} true)))))))

(defn- scoped-statement-jvm [cache producer?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.precompile
                    'aguafria.zig.explain 'clojure.java.io)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (if ~producer?
             (let [fail!# (fn [& _#] (throw (ex-info "Preparation invoked native code" {})))]
               (with-redefs [aguafria.zig.runtime/invoke! fail!#
                             aguafria.zig.runtime/invoke-with-result! fail!#]
                 (let [report# (aguafria.zig.precompile/precompile!
                                {:namespaces ['aguafria.zig.jvm-scoped-statement-fixture]
                                 :analyze ['aguafria.zig.jvm-scoped-statement-fixture]
                                 :parallelism 1})]
                   (prn {:bundles (:bundles report#) :coverage (:coverage report#)
                         :comptime-capture-handlers
                         (vec (for [entry# (:analysis report#)
                                    operation# (:operations entry#)
                                    :when (and (:scoped-form operation#)
                                               (= (symbol "inline-count")
                                                  (:declaration-name operation#)))
                                    handler# (:handlers operation#)]
                                (select-keys handler# [:status :reason])))
                         :failed-handlers
                         (count (filter #(= :failed (:status %))
                                        (mapcat :handlers
                                                (mapcat :operations (:analysis report#)))))}))))
             (do
               (require 'aguafria.zig.jvm-scoped-statement-fixture)
               (let [forms# (with-open [reader# (java.io.PushbackReader.
                                                 (clojure.java.io/reader
                                                  (clojure.java.io/resource
                                                   "aguafria/zig/jvm_scoped_statement_fixture.clj")))]
                              (doall (take-while some?
                                                 (repeatedly #(read {:eof nil} reader#)))))
                     bodies# (into {} (for [form# forms#
                                            :when (and (seq? form#)
                                                       (= "a/defn" (str (first form#))))]
                                        [(second form#) (cons (symbol "do") (drop 4 form#))]))
                     expected# (mapv symbol ["comptime-loop-capture"
                                             "contextual-switch" "label-value-collision"
                                             "while-error-capture" "for-else-value"])
                     _# (when-not (= (set expected#) (set (keys bodies#)))
                          (throw (ex-info "Missing scoped fixture bodies" {:found (keys bodies#)})))
                     events# (atom [])
                     results# (binding [*ns* (the-ns 'aguafria.zig.jvm-scoped-statement-fixture)
                                        aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                                (mapv (fn [name#]
                                        (try
                                          (let [body# (get bodies# name#)
                                                body# (if (= "while-error-capture" (str name#))
                                                        (list (symbol "let") [(symbol "err") 7] body#)
                                                        body#)]
                                            {:name name# :value (aguafria.zig/value (eval body#))})
                                          (catch Throwable error#
                                            {:name name# :causes (mapv ex-message
                                                                       (take-while some?
                                                                                   (iterate ex-cause error#)))})))
                                      expected#))]
                 (prn {:results results# :events @events#}))))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Scoped statement JVM failed" result)))
    (edn/read-string (:out result))))

(deftest fresh-scoped-controls-share-the-compiler-prepared-pack
  (let [cache (str (java.nio.file.Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "scoped-statements-" (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (scoped-statement-jvm cache true)
        consumer (scoped-statement-jvm cache false)
        events (:events consumer)
        hits (filter #(= :bundle-cache-hit (:event %)) events)
        loads (filter #(= :bundle-loaded (:event %)) events)
        misses (filter #(or (#{:compiled :compile-failed} (:event %))
                            (and (= :disk-cache-hit (:event %))
                                 (str/starts-with? (str (:module %)) "aguafria.jvm."))) events)]
    (spit (io/file cache "producer.edn") (pr-str producer))
    (spit (io/file cache "consumer.edn") (pr-str consumer))
    (is (= 0 (get-in producer [:coverage :namespaces :baseline-failures])))
    (is (= [{:status :unsupported :reason :comptime-only-mutation}]
           (:comptime-capture-handlers producer)))
    (is (= 0 (:failed-handlers producer)))
    (is (= [{:name 'comptime-loop-capture :value {:ok nil}}
            {:name 'contextual-switch :value {:ok nil}}
            {:name 'label-value-collision :value {:ok nil}}
            {:name 'while-error-capture :value {:ok nil}}
            {:name 'for-else-value :value {:ok nil}}]
           (:results consumer)) (pr-str (:results consumer)))
    (is (empty? misses) (pr-str misses))
    (is (seq hits))
    (is (= 1 (count loads)))
    (is (every? #(= (get-in producer [:bundles :packs 0 :id]) (:bundle-id %)) hits))))
