(ns aguafria.zig.jvm-metadata-parity-test
  (:require [aguafria.zig :as a]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest native-and-prepared-parameters-have-the-same-source-identity
  (let [context (create-ns (symbol (str "aguafria.metadata-parity-" (random-uuid))))
        module (str (ns-name context))
        registry @#'runtime/registry]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (eval '(a/defstruct Item [[:x :u8]])))
      (let [type (symbol module "Item")
            prepared-type (emitter/qualify-type context type)
            handle (value/native-value {:module module :kind :value :type 'Item}
                                       (constantly {:representation :native}))
            declarations [{:type :anytype :properties {:jvm/literal? true}}]
            [prepared actual]
            (binding [*ns* context]
              [(#'jvm/call-inputs declarations [(jvm/->PreparedOperand prepared-type)])
               (#'jvm/call-inputs declarations [handle])])]
        (is (nil? (meta (value/qualified-type handle))))
        (is (some? (:aguafria/zig-reference (meta (:type (first (:parameters actual)))))))
        (is (= (runtime/adapter-fingerprint (:parameters prepared))
               (runtime/adapter-fingerprint (:parameters actual))))
        (is (= (emitter/emit-type (:type (first (:parameters prepared))))
               (emitter/emit-type (:type (first (:parameters actual))))))
        (is (identical? handle (first (:arguments actual)))))
      (finally
        (swap! registry dissoc module)
        (remove-ns (ns-name context))))))

(deftest scoped-parameters-rebase-to-the-caller-before-hashing
  (let [context (create-ns (symbol (str "aguafria.scoped-parity-" (random-uuid))))
        module (str (ns-name context))
        registry @#'runtime/registry]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (eval '(a/defstruct Item [[:x :u8]])))
      (let [type (symbol module "Item")
            imported (emitter/qualify-type (the-ns 'aguafria.zig.jvm) type)
            local (emitter/qualify-type context type)
            plan (fn [type]
                   (#'jvm/prepare-scoped-plan!
                    (ns-name context) '(aguafria.zig/block) []
                    {:parameters [{:name 'input_0 :type type}] :expression-arguments []}
                    false nil))
            expected (plan imported)
            actual (plan local)]
        (is (not= (runtime/adapter-fingerprint imported) (runtime/adapter-fingerprint local)))
        (is (= (:function expected) (:function actual)))
        (is (= (runtime/adapter-fingerprint (:parameters expected))
               (runtime/adapter-fingerprint (:parameters actual)))))
      (finally
        (swap! registry dissoc module)
        (remove-ns (ns-name context))))))

(deftest retained-type-sources-require-closed-names-and-compiler-proof
  (let [context (the-ns 'aguafria.zig.jvm-metadata-parity-test)
        calls (atom [])
        proofs (atom [])
        call {:function 'aguafria.zig/type :caller (ns-name context)
              :args [{:comptime-type :u8}]
              :source-arguments ['(aguafria.keyword/TypeOf 1)]}]
    (with-redefs-fn
      {#'jvm/precompile-call-base! #(do (swap! calls conj %) {:status :prepared})
       #'jvm/confirm-type-argument-source!
       #(swap! proofs conj [%2 %3])}
      (fn []
        (jvm/precompile-call! call)
        (is (= 2 (count @calls)))
        (is (= (:args call) (:args (first @calls))))
        (is (= 1 (count @proofs)))
        (is (seq? (get-in (second @calls) [:args 0 :comptime-type])))
        (reset! calls [])
        (reset! proofs [])
        (jvm/precompile-call! (assoc call :source-arguments ['missing-lexical-operand]))
        (is (= 1 (count @calls)))
        (is (empty? @proofs))
        (reset! calls [])
        (jvm/precompile-call! (assoc call :source-arguments [:u8]))
        (is (= 1 (count @calls)))))
    (with-redefs [runtime/inspect-module! (fn [& _] {:exit 1 :err "type mismatch"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not confirm"
                           (#'jvm/confirm-type-argument-source! context :u8 :u16))))))

(deftest explicit-tag-layout-inherits-only-the-compiled-type-root
  (let [context (create-ns (symbol (str "aguafria.tag-layout-" (random-uuid))))
        module (str (ns-name context))
        registry @#'runtime/registry
        requests (atom [])
        plans (atom [])]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (eval '(a/defenum Tag [:ok]))
        (eval '(a/defunion Result {:type Tag} [[:ok :u8]]))
        (eval '(a/defenum Unused [:no])))
      (let [declarations (runtime/registered-declarations module)
            result (first (filter #(= 'Result (:name %)) declarations))
            tag (first (filter #(= 'Tag (:name %)) declarations))
            snapshot {:declarations [tag result]
                      :development-root-declarations [tag result]}]
        (is (seq (:type-dependency-fingerprints result)))
        (is (= [:u8]
               (mapv :type (:members (emitter/container-description context (:value result))))))
        (with-redefs-fn
          {#'runtime/materialize-declaration-generation!
           (fn [declaration request]
             (swap! plans conj [(:name declaration) request])
             {:compiled {:compilation-snapshot snapshot}})
           #'runtime/ensure-native-type-binding!
           (fn [owner type]
             (swap! requests conj [owner type
                                   (get-in @runtime/*prepared-namespace-images* [owner :snapshot])]))}
          #(do
             (#'runtime/precompile-type-layout-dependencies! result)
             (is (= [['Result :jvm-type-declaration-keys]] @plans))
             (is (= [[module 'Tag snapshot]] @requests))
             (reset! plans [])
             (#'runtime/precompile-type-layout-dependencies! tag)
             (is (empty? @plans)))))
      (finally
        (swap! registry dissoc module)
        (remove-ns (ns-name context))))))

(defn- metadata-parity-jvm [cache producer?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.precompile
                    'aguafria.zig.explain 'clojure.java.io)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (binding [aguafria.zig.runtime/*source-only-registration?* true]
             (require 'aguafria.zig.jvm-metadata-parity-fixture))
           (if ~producer?
             (let [fail!# (fn [& _#] (throw (ex-info "Producer invoked native body" {})))]
               (with-redefs [aguafria.zig.runtime/invoke! fail!#
                             aguafria.zig.runtime/invoke-with-result! fail!#]
                 (let [report# (aguafria.zig.precompile/precompile!
                                {:namespaces ['aguafria.zig.jvm-metadata-parity-fixture]
                                 :analyze ['aguafria.zig.jvm-metadata-parity-fixture]
                                 :parallelism 1 :report-file ~(str cache "/report.edn")})]
                   (prn {:bundles (:bundles report#) :coverage (:coverage report#)}))))
             (let [forms# (with-open [reader# (java.io.PushbackReader.
                                              (clojure.java.io/reader
                                               (clojure.java.io/resource
                                                "aguafria/zig/jvm_metadata_parity_fixture.clj")))]
                            (binding [*read-eval* false]
                              (into [] (take-while some?)
                                    (repeatedly #(read {:eof nil} reader#)))))
                   bodies# (filter #(and (seq? %) (symbol? (first %))
                                         (= "deftest" (name (first %)))) forms#)
                   events# (atom [])
                   outputs# (binding [*ns* (the-ns 'aguafria.zig.jvm-metadata-parity-fixture)
                                      aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                              (mapv #(aguafria.zig/value (eval (cons 'do (drop 2 %)))) bodies#))]
               (when (some #(and (map? %) (contains? % :error)) outputs#)
                 (throw (ex-info "Body returned a native error" {:outputs outputs#})))
               (prn {:outputs outputs# :events @events#})))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED" "-cp"
                         (System/getProperty "java.class.path") "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Metadata parity JVM failed" result)))
    (edn/read-string (:out result))))

(deftest compiler-owned-metadata-readers-reuse-the-producer-pack
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "metadata-parity-" (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (metadata-parity-jvm cache true)
        consumer (metadata-parity-jvm cache false)
        events (:events consumer)
        packs (filter #(= :bundle-loaded (:event %)) events)
        hits (filter #(= :bundle-cache-hit (:event %)) events)
        misses (filter #(and (contains? #{:compiled :disk-cache-hit} (:event %))
                              (nil? (:bundle-id %))) events)
        pack (get-in producer [:bundles :packs 0 :id])]
    (is (= 2 (count (:outputs consumer))))
    (is (= [nil {:ok nil}] (:outputs consumer)))
    (is (= 1 (count packs)) (pr-str events))
    (is (seq hits))
    (is (every? #(= pack (:bundle-id %)) hits))
    (is (str/includes? (or (:path (first packs)) "") (str "/bundles/" pack "/")))
    (is (empty? misses) (pr-str {:cache cache :misses misses}))
    (is (empty? (filter #(= :compiled (:event %)) events)) (pr-str events))))
