(ns aguafria.zig.jvm-scoped-destination-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.emitter :as emitter]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- emitted-contexts [return-type body]
  (let [context (the-ns 'aguafria.zig.jvm-scoped-destination-test)
        declaration (emitter/prepare-declaration
                     context {:kind :fn :name 'fixture :return return-type
                              :args [{:name 'input :type :u32}]
                              :body [body] :implicit-return? true})
        observations (atom [])
        source (emitter/emit-module (str (ns-name context)) [declaration])
        observed-source
        (binding [emitter/*expression-observer*
                  (fn [observation]
                    (swap! observations conj
                           (select-keys observation [:form :result-context :result-context-origin
                                                     :result-context-envelope]))
                    (:source observation))]
          (emitter/emit-module (str (ns-name context)) [declaration]))]
    {:source source :observed-source observed-source :observations @observations}))

(deftest native-result-arms-carry-the-real-destination-without-changing-source
  (let [result (emitted-contexts
                :u32 '(a/switch input (case [0] (k/intFromFloat 42.0)) (case-else 7)))
        scopes (filter #(= 'switch (some-> % :form first)) (:observations result))]
    (is (= (:source result) (:observed-source result)))
    (is (seq scopes))
    (is (every? #(= :u32 (:result-context %)) scopes))
    (is (every? #(= :return-destination (:result-context-origin %)) scopes)))
  (let [result (emitted-contexts
                :u64 '(aguafria.zig/raw "consume(switch (input) { 0 => 1, else => 2 })"))]
    ;; An enclosing return is not an operand's destination contract.
    (is (= (:source result) (:observed-source result)))))

(deftest peer-result-arms-use-the-original-native-envelope
  (let [parent '(a/if-capture {:payload [value] :error [err]} input
                              (k/+ value 3)
                              (k/switch err (case [(a/error-value :Rejected)] nil)))
        result (emitted-contexts :void (list 'let ['choice parent] '(k/= :_ choice)))
        inner (filter #(= 'switch (some-> % :form first)) (:observations result))]
    (is (= (:source result) (:observed-source result)))
    (is (seq inner))
    (is (every? #(= :peer-envelope (:result-context-origin %)) inner))
    (is (every? #(symbol? (:result-context %)) inner))
    (is (every? #(= 'if-capture (first (:result-context-envelope %))) inner)))
  (let [parent '(a/catch-capture [err] input
                                 (k/switch err (case [(a/error-value :Rejected)] nil)))
        result (emitted-contexts :void (list 'let ['choice parent] '(k/= :_ choice)))
        inner (filter #(= 'switch (some-> % :form first)) (:observations result))]
    (is (seq inner))
    (is (every? #(= :peer-envelope (:result-context-origin %)) inner))
    (is (every? #(= 'catch-capture (first (:result-context-envelope %))) inner))))

(deftest external-exits-and-ordinary-operands-do-not-acquire-a-return-contract
  (let [exit '(a/switch input (case [0] (k/return 7)) (case-else 2))
        result (emitted-contexts :u32 exit)
        scopes (filter #(= 'switch (some-> % :form first)) (:observations result))]
    (is (seq scopes))
    (is (every? #(nil? (:result-context-origin %)) scopes)))
  (let [result (emitted-contexts
                :u64 '(k/+ (a/switch input (case [0] 1) (case-else 2)) 3))
        scopes (filter #(= 'switch (some-> % :form first)) (:observations result))]
    (is (seq scopes))
    (is (every? #(nil? (:result-context-origin %)) scopes))))

(defn- destination-jvm [cache producer?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.precompile
                    'aguafria.zig.explain 'clojure.java.io)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (binding [aguafria.zig.runtime/*source-only-registration?* true]
             (require 'aguafria.zig.jvm-scoped-destination-fixture))
           (if ~producer?
             (let [fail!# (fn [& _#] (throw (ex-info "Producer invoked a native body" {})))]
               (with-redefs [aguafria.zig.runtime/invoke! fail!#
                             aguafria.zig.runtime/invoke-with-result! fail!#]
                 (let [report# (aguafria.zig.precompile/precompile!
                                {:analyze ['aguafria.zig.jvm-scoped-destination-fixture]
                                 :parallelism 1 :report-file ~(str cache "/report.edn")})]
                   (prn {:bundles (:bundles report#) :coverage (:coverage report#)
                         :profiles (vec (for [entry# (:analysis report#)
                                              operation# (:operations entry#)
                                              :when (:scope-result-context-origin operation#)]
                                          (select-keys operation#
                                                       [:id :scope-result-context-origin
                                                        :scope-result-envelope :signatures :handlers])))}))))
             (let [events# (atom [])
                   equal-var# (requiring-resolve 'aguafria.std.testing/expectEqual)
                   equal# @equal-var#
                   checked-equal#
                   (fn [& arguments#]
                     (let [result# (apply equal# arguments#)
                           decoded# (aguafria.zig/value result#)]
                       (when (:error decoded#)
                         (throw (ex-info "Native expectEqual returned an error"
                                         {:result decoded#})))
                       result#))
                   forms# (with-open [reader# (java.io.PushbackReader.
                                              (clojure.java.io/reader
                                               (clojure.java.io/resource
                                                "aguafria/zig/jvm_scoped_destination_fixture.clj")))]
                            (binding [*read-eval* false]
                              (into [] (take-while some?)
                                    (repeatedly #(read {:eof nil} reader#)))))
                   bodies# (mapv #(cons (symbol "do") (drop 2 %))
                                 (filter #(and (seq? %) (= "a/deftest" (str (first %)))) forms#))
                   _# (when-not (= 2 (count bodies#))
                        (throw (ex-info "Missing destination fixture bodies" {:count (count bodies#)})))
                   outputs# (with-redefs-fn
                              {equal-var# checked-equal#}
                              #(binding [*ns* (the-ns 'aguafria.zig.jvm-scoped-destination-fixture)
                                         aguafria.zig.explain/*reporter* (fn [event#] (swap! events# conj event#))]
                                 (mapv (fn [body#] (aguafria.zig/value (eval body#))) bodies#)))]
               (prn {:outputs outputs# :events @events#})))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Destination fixture JVM failed" result)))
    (edn/read-string (:out result))))

(deftest compiler-result-contracts-reuse-the-fresh-producer-pack
  (let [cache (str (java.nio.file.Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "scoped-destination-" (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (destination-jvm cache true)
        _ (spit (io/file cache "producer.edn") (pr-str producer))
        _ (when-not (and (= 0 (get-in producer [:coverage :namespaces :baseline-failures]))
                         (= 0 (get-in producer [:coverage :runtime-candidates :not-fully-prepared])))
            (throw (ex-info "Destination fixture producer was incomplete"
                            {:cache cache :producer producer})))
        consumer (destination-jvm cache false)
        events (:events consumer)
        hits (filter #(= :bundle-cache-hit (:event %)) events)
        loads (filter #(= :bundle-loaded (:event %)) events)
        misses (filter #(or (#{:compiled :compile-failed} (:event %))
                            (and (= :disk-cache-hit (:event %)) (nil? (:bundle-id %)))) events)
        profiles (:profiles producer)
        schemas (set (mapcat #(map peek (:signatures %)) profiles))]
    (spit (io/file cache "producer.edn") (pr-str producer))
    (spit (io/file cache "consumer.edn") (pr-str consumer))
    (is (= 0 (get-in producer [:coverage :namespaces :baseline-failures])))
    (is (= 0 (get-in producer [:coverage :runtime-candidates :not-fully-prepared])))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers profiles)) (pr-str profiles))
    (is (contains? schemas :usize))
    (is (contains? schemas :u32))
    (is (contains? schemas [:optional :u32]))
    (is (seq (filter #(= :peer-envelope (:scope-result-context-origin %)) profiles)))
    (is (= [{:ok nil} {:ok nil}] (:outputs consumer)))
    (is (not-any? :error (:outputs consumer)))
    (is (seq hits))
    (is (= 1 (count loads)))
    (is (every? #(= (get-in producer [:bundles :packs 0 :id]) (:bundle-id %)) hits))
    (is (empty? misses) (pr-str misses))))
