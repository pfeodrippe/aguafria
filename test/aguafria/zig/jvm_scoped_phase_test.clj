(ns aguafria.zig.jvm-scoped-phase-test
  (:require [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]))

(deftest comptime-capture-constants-keep-the-compiler-type
  (let [source (deref (ns-resolve 'aguafria.zig.jvm 'scoped-comptime-source))]
    (is (= '(aguafria.keyword/as 1 :u1) (source :u1 {:comptime 1})))
    (is (= '(aguafria.keyword/as true :bool) (source :bool {:comptime true})))
    (is (= '(aguafria.keyword/as :u16 :type)
           (source :type {:comptime-type :u16})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no closed native value"
                         (source :u1 nil)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"detached lexical source"
                         (source :u1 {:comptime-expression
                                      (with-meta 'kind {:aguafria/local? true})})))))

(deftest comptime-capture-storage-contract-fails-before-registration
  (doseq [address [[:* :u1] [:*const :u8]]]
    (let [calls (atom 0)]
      (with-redefs-fn
        {(ns-resolve 'aguafria.zig.jvm 'prepare-scoped-plan!)
         (fn [& _] (swap! calls inc) (throw (ex-info "Unexpected planner" {})))}
        #(is (thrown-with-msg?
              clojure.lang.ExceptionInfo #"disagrees with its native address"
              (jvm/precompile-scoped!
               {:caller 'aguafria.zig.jvm-scoped-phase-test
                :form '(aguafria.zig/switch kind (case [0] 1) (case [1] 2))
                :captures ['kind] :types [{:comptime-capture [:u1 {:comptime 1}]} address]
                :result? true}))))
      (is (zero? @calls)))))

(deftest peer-type-proof-identity-keeps-compiler-source-and-options
  (let [key (deref (ns-resolve 'aguafria.zig.jvm 'compiler-metadata-proof-key))
        profile [:peer-number-type-v1 [:comptime_int :usize] :usize]
        identity (fn [options compiler source]
                   (with-redefs [runtime/configuration (constantly options)
                                 runtime/toolchain-information (constantly compiler)]
                     (key profile source)))
        options {:optimize "debug" :target nil :cache-dir "producer"}
        compiler {:zig-version "0.17.0" :sha256 "compiler-one"}
        baseline (identity options compiler "real native proof")]
    (is (= baseline (identity (assoc options :cache-dir "consumer") compiler "real native proof")))
    (is (not= baseline (identity options compiler "different native proof")))
    (is (not= baseline (identity options (assoc compiler :sha256 "compiler-two") "real native proof")))
    (is (not= baseline (identity (assoc options :target "x86_64-linux") compiler "real native proof")))
    (is (not= baseline
              (with-redefs [runtime/configuration (constantly options)
                            runtime/toolchain-information (constantly compiler)]
                (key [:peer-number-type-v2 [:comptime_int :usize] :usize] "real native proof"))))))

(defn- phase-jvm [cache producer?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.keyword 'aguafria.zig.runtime
                    'aguafria.zig.precompile 'aguafria.zig.explain 'aguafria.zig.jvm
                    'aguafria.zig.value 'clojure.java.io 'clojure.java.shell 'clojure.edn)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (binding [aguafria.zig.runtime/*source-only-registration?* true]
             (require 'aguafria.zig.jvm-scoped-phase-fixture))
           (if ~producer?
             (let [fail!# (fn [& _#] (throw (ex-info "Producer invoked native code" {})))
                   report# (with-redefs [aguafria.zig.runtime/invoke! fail!#
                                         aguafria.zig.runtime/invoke-with-result! fail!#]
                             (aguafria.zig.precompile/precompile!
                              {:analyze ['aguafria.zig.jvm-scoped-phase-fixture]
                               :parallelism 1 :report-file ~(str cache "/report.edn")}))]
               (prn {:bundles (:bundles report#) :coverage (:coverage report#)}))
             (let [report# (clojure.edn/read-string (slurp ~(str cache "/report.edn")))
                   operations# (mapcat :operations (:analysis report#))
                   phases# (filter #(seq (:scope-capture-contracts %)) operations#)
                   events# (atom [])
                   commands# (atom [])
                   shell# clojure.java.shell/sh
                   forms# (with-open [r# (java.io.PushbackReader.
                                         (clojure.java.io/reader
                                          (clojure.java.io/resource "aguafria/zig/jvm_scoped_phase_fixture.clj")))]
                            (binding [*read-eval* false]
                              (into [] (take-while some?) (repeatedly #(read {:eof nil} r#)))))
                   body# (first (filter #(and (seq? %) (= (symbol "phase-results") (second %))) forms#))
                   _# (when-not body# (throw (ex-info "Missing authored phase fixture body" {})))
                   equal-var# (requiring-resolve 'aguafria.std.testing/expectEqual)
                   equal# @equal-var#
                   outputs#
                   (with-redefs-fn
                     {#'clojure.java.shell/sh
                      (fn [& command#]
                        (let [start# (System/nanoTime)
                              stack# (mapv str (.getStackTrace (Thread/currentThread)))]
                          (try
                            (apply shell# command#)
                            (finally
                              (swap! commands# conj
                                     {:command (vec command#) :stack stack#
                                      :elapsed-ms (/ (- (System/nanoTime) start#) 1e6)})))))
                      equal-var# (fn [& args#]
                                   (let [v# (apply equal# args#) decoded# (aguafria.zig/value v#)]
                                     (when (:error decoded#)
                                       (throw (ex-info "Native equality failed" {:result decoded#})))
                                     v#))}
                     #(binding [*ns* (the-ns 'aguafria.zig.jvm-scoped-phase-fixture)
                                aguafria.zig.explain/*reporter* (fn [event#] (swap! events# conj event#))]
                        (let [result# (aguafria.zig/value (eval (cons (symbol "do") (drop 2 body#))))
                              direct#
                              (vec (for [op# phases# signature# (:signatures op#)
                                         :let [form# (vary-meta (:scoped-form op#) assoc
                                                               :aguafria/scoped-capture-contracts
                                                               (:scope-capture-contracts op#))
                                               kind# (get-in signature# [0 :comptime-capture 1 :comptime])
                                               value# (aguafria.zig.jvm/invoke-scoped!
                                                       'aguafria.zig.jvm-scoped-phase-fixture
                                                       form# {(symbol "kind") kind#} true
                                                       (when (:scope-result-context? op#) (peek signature#)))
                                               decoded# (aguafria.zig/value value#)]]
                                     (cond
                                       (aguafria.zig.value/zig-error? decoded#) [:error (:name decoded#)]
                                       (aguafria.zig.value/zig-type? decoded#)
                                       [:type (:type (aguafria.zig.value/type-info decoded#))]
                                       :else decoded#)))
                              before-warm# (count @commands#)
                              warm-body# (aguafria.zig/value (eval (cons (symbol "do") (drop 2 body#))))
                              warm-commands# (- (count @commands#) before-warm#)
                              rejected#
                              (vec (for [method# [:segment :address]
                                         :let [backing# (aguafria.keyword/as 1 :u1)
                                               v# (aguafria.zig.value/native-value
                                                   (assoc (aguafria.zig.value/info backing#) :kind :const)
                                                   (fn [] (assoc (aguafria.zig.value/realize! backing#)
                                                                 :owners [backing#])))
                                               _# (aguafria.zig.value/retain-coercion-expression!
                                                   v# '(aguafria.keyword/as 1 :u1))
                                               storage# (aguafria.zig.value/segment v#)
                                               pointer# (when (= :address method#)
                                                          (aguafria.zig.value/address-value v# false))
                                               destination# (if pointer#
                                                              (.reinterpret
                                                               (.get (aguafria.zig.value/segment pointer#)
                                                                     java.lang.foreign.ValueLayout/ADDRESS 0)
                                                               (.byteSize storage#))
                                                              storage#)
                                               _# (.set destination# java.lang.foreign.ValueLayout/JAVA_BYTE 0 (byte 0))
                                               before# (count @events#)
                                               rejection#
                                               (try
                                                 (aguafria.zig.jvm/invoke-scoped!
                                                  'aguafria.zig.jvm-scoped-phase-fixture
                                                  (with-meta '(aguafria.zig/switch kind (case [0] 0) (case [1] 1))
                                                    {:aguafria/scoped-capture-contracts
                                                     {(symbol "kind") {:phase :comptime :type :u1}}})
                                                  {(symbol "kind") v#} true :u1)
                                                 :unexpected-success
                                                 (catch clojure.lang.ExceptionInfo error# (:reason (ex-data error#))))]]
                                     {:method method# :kind (:kind (aguafria.zig.value/info v#))
                                      :current (aguafria.zig/value v#)
                                      :reason rejection# :new-events (- (count @events#) before#)}))]
                          {:body result# :direct direct# :rejected rejected#
                           :warm-body warm-body# :warm-commands warm-commands#})))]
               (prn (assoc outputs# :events @events# :commands @commands#))))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED" "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result)) (throw (ex-info "Scoped phase JVM failed" result)))
    (edn/read-string (:out result))))

(deftest typed-comptime-specializations-reuse-a-fresh-producer-pack
  (let [cache (str (java.nio.file.Files/createTempDirectory
                   (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                   "scoped-phase-" (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (phase-jvm cache true)
        _ (spit (io/file cache "producer.edn") (pr-str producer))
        _ (when-not (and (= 0 (get-in producer [:coverage :namespaces :baseline-failures]))
                         (= 0 (get-in producer [:coverage :runtime-candidates :not-fully-prepared])))
            (throw (ex-info "Incomplete phase fixture producer" {:cache cache :producer producer})))
        consumer (phase-jvm cache false)
        events (:events consumer)
        hits (filter #(= :bundle-cache-hit (:event %)) events)
        loads (filter #(= :bundle-loaded (:event %)) events)
        misses (filter #(or (#{:compiled :compile-failed} (:event %))
                            (and (= :disk-cache-hit (:event %)) (nil? (:bundle-id %)))) events)]
    (spit (io/file cache "consumer.edn") (pr-str consumer))
    (is (nil? (:body consumer)))
    (is (= #{[:error "Left"] [:error "Right"]
             [:type [:error-set [:Left]]] [:type [:error-set [:Right]]] 1}
           (set (:direct consumer))))
    (is (= 6 (count (:direct consumer))))
    (is (every? #(and (= :const (:kind %)) (= 0 (:current %))
                     (= :comptime-capture-source-unavailable (:reason %))
                     (zero? (:new-events %))) (:rejected consumer)))
    (is (= #{:segment :address} (set (map :method (:rejected consumer)))))
    (is (seq hits))
    (is (= 1 (count loads)))
    (is (every? #(= (get-in producer [:bundles :packs 0 :id]) (:bundle-id %)) hits))
    (is (empty? misses) (pr-str misses))
    ;; A fresh process may verify the cached compiler executable identity once.
    ;; No semantic compiler command (including an unrecognized command) is okay.
    (is (every? #(let [command (:command %)]
                   (and (= 4 (count command)) (= "version" (second command))
                        (= :dir (nth command 2))))
                (:commands consumer)) (pr-str (:commands consumer)))
    (is (<= (count (:commands consumer)) 1) (pr-str (:commands consumer)))
    (is (nil? (:warm-body consumer)))
    (is (zero? (:warm-commands consumer)))))
