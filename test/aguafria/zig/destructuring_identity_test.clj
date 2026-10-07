(ns aguafria.zig.destructuring-identity-test
  (:require [aguafria.zig.emitter :as emitter]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(defn- advance-unrelated-destructuring! [context count]
  (dotimes [_ count]
    (gensym "unrelated_")
    (emitter/prepare-declaration
     context
     '{:kind :fn :name unrelated :return :u32
       :args [{:name {:keys [x]} :type :anytype}]
       :body [(let [{:keys [y]} {:y x}] y)]})))

(deftest binding-identities-are-lexical-and-collision-free
  (let [context (the-ns 'aguafria.zig.destructuring-identity-test)
        declaration
        '{:kind :fn :name sample :return :u32
          :args [{:name {:keys [x]} :type :anytype}
                 {:name __aguafria_binding_0 :type :u32}]
          :body [(let [{:keys [y]} {:y x}
                       __aguafria_binding_2 __aguafria_binding_0
                       {:keys [z]} {:z y}]
                   (+ z __aguafria_binding_2))]}
        first-declaration (emitter/prepare-declaration context declaration)
        first-source (emitter/emit-declaration first-declaration)
        _ (advance-unrelated-destructuring! context 31)
        second-declaration (emitter/prepare-declaration context declaration)]
    (is (= first-declaration second-declaration))
    (is (= first-source (emitter/emit-declaration second-declaration)))
    (is (= '__aguafria_binding_1 (get-in first-declaration [:args 0 :name])))
    (is (str/includes? first-source "__aguafria_binding_0: u32"))
    (is (str/includes? first-source "const __aguafria_binding_2 = __aguafria_binding_0;"))
    (is (str/includes? first-source "const __aguafria_binding_3 = __aguafria_binding_1;")))
  (let [context (the-ns 'aguafria.zig.destructuring-identity-test)
        form '(let [{:keys [x]} {:x 7}]
                (let [{:keys [y]} {:y x}] y))
        first-form (emitter/qualify-form context form)
        first-source (emitter/emit-expr context form)]
    (advance-unrelated-destructuring! context 17)
    (is (= first-form (emitter/qualify-form context form)))
    (is (= first-source (emitter/emit-expr context form)))
    (is (str/includes? first-source "const __aguafria_binding_0"))
    (is (str/includes? first-source "const __aguafria_binding_1")))
  (let [context (the-ns 'aguafria.zig.destructuring-identity-test)
        declaration
        '{:kind :const :name Methods
          :value (container {:kind :struct}
                            [(fn-decl read :u32 [[{:keys [x]} :anytype]] x)])}
        source #(emitter/emit-declaration (emitter/prepare-declaration context declaration))
        first-source (source)]
    (advance-unrelated-destructuring! context 23)
    (is (= first-source (source)))
    (is (= declaration (emitter/validate-declaration-references! context declaration))))
  (let [context (the-ns 'aguafria.zig.destructuring-identity-test)
        ;; Scoped mutable captures replace names with address/deref syntax,
        ;; not identifiers. Reserve names inside that syntax without treating
        ;; the entire native operand expression as a name.
        replacement '(aguafria.zig/deref
                      (aguafria.keyword/as
                       (aguafria.keyword/ptrFromInt __aguafria_binding_0) [:* :i32]))]
    (binding [emitter/*local-type-bindings* {'counter false}
              emitter/*local-name-bindings* {'counter replacement}]
      (let [prepared (emitter/qualify-form context '(let [{:keys [x]} {:x counter}] x))]
        (is (= '__aguafria_binding_1 (first (second prepared))))
        (is (= replacement (:x (second (second prepared)))))))))

(deftest declarations-without-destructuring-do-not-scan-namespace-identifiers
  (let [visited (atom 0)
        context (the-ns 'aguafria.zig.destructuring-identity-test)
        state ((var emitter/binding-temporary-state) '{:kind :fn :name plain :args [] :body []})
        render-name @(var emitter/identifier-source)]
    (is (delay? state))
    (is (not (realized? state)))
    (with-redefs-fn {(var emitter/identifier-source)
                     (fn [identifier] (swap! visited inc) (render-name identifier))}
      #(binding [emitter/*registered-declaration-names*
                 (into #{} (map (comp symbol (partial str "unrelated_"))) (range 10000))]
         (is (map? (emitter/prepare-declaration
                    context '{:kind :fn :name plain :args [] :return :u32 :body [7]})))))
    (is (zero? @visited))))

(defn- identity-jvm [cache producer?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.precompile
                    'aguafria.zig.emitter 'aguafria.zig.explain 'aguafria.zig.artifact
                    'aguafria.zig.destructuring-identity-test
                    'clojure.java.io 'clojure.java.shell)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           ((var advance-unrelated-destructuring!) *ns* ~(if producer? 37 3))
           (if ~producer?
             (let [fail!# (fn [& _#] (throw (ex-info "Preparation invoked native code" {})))
                   report# (with-redefs [aguafria.zig.runtime/invoke! fail!#
                                         aguafria.zig.runtime/invoke-with-result! fail!#]
                             (aguafria.zig.precompile/precompile!
                              {:analyze ['learn.example.test-anonymous-struct]
                               :parallelism 1 :report-file ~(str cache "/report.edn")}))]
               (prn {:coverage (:coverage report#) :bundles (:bundles report#)
                     :baseline (get-in report# [:analysis 0 :baseline])
                     :namespace-images (:namespace-images report#)}))
             (let [events# (atom []) commands# (atom []) command-times# (atom [])
                   original-shell# clojure.java.shell/sh
                   outputs#
                   (with-redefs [clojure.java.shell/sh
                                 (fn [& args#]
                                   (swap! commands# conj (vec args#))
                                   (let [started# (System/nanoTime)]
                                     (try (apply original-shell# args#)
                                          (finally
                                            (swap! command-times# conj
                                                   {:command (vec args#)
                                                    :duration-ms (/ (- (System/nanoTime) started#) 1e6)})))))]
                     (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                       ;; Ordinary require must reuse startup and test-definition
                       ;; checks too; source-only registration would conceal them.
                       (require 'learn.example.test-anonymous-struct)
                       (let [forms# (with-open [reader# (java.io.PushbackReader.
                                                         (clojure.java.io/reader
                                                          (clojure.java.io/resource
                                                           "learn/example/test_anonymous_struct.clj")))]
                                      (binding [*read-eval* false]
                                        (into [] (take-while some?)
                                              (repeatedly #(read {:eof nil} reader#)))))
                             body# (first (filter #(and (seq? %) (= (symbol "a/deftest") (first %))) forms#))]
                         (binding [*ns* (the-ns 'learn.example.test-anonymous-struct)]
                           {:body-results (mapv (fn [_#]
                                                  (aguafria.zig/value
                                                   (eval (cons (symbol "do") (drop 2 body#)))))
                                                (range 2))
                            :native-test ((resolve (symbol "fully-anonymous-struct")))}))))]
               (prn (assoc outputs# :events @events# :commands @commands#
                           :command-times @command-times#))))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED" "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Destructuring identity JVM failed" result)))
    (edn/read-string (:out result))))

(deftest different-destructuring-histories-reuse-startup-checks-and-call-pack
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "destructuring-identity-" (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (identity-jvm cache true)
        _ (spit (io/file cache "producer.edn") (pr-str producer))
        consumer (identity-jvm cache false)
        _ (spit (io/file cache "consumer.edn") (pr-str consumer))
        events (:events consumer)
        hits (filter #(= :bundle-cache-hit (:event %)) events)
        loads (filter #(= :bundle-loaded (:event %)) events)
        misses (filter #(or (#{:compiled :compile-failed} (:event %))
                            (and (= :disk-cache-hit (:event %)) (nil? (:bundle-id %))
                                 (str/starts-with? (:module %) "aguafria.jvm."))) events)]
    (is (zero? (get-in producer [:baseline :exit])))
    (is (= 14 (get-in producer [:coverage :operations :fully-prepared])))
    (is (= 14 (get-in producer [:coverage :operations :total])))
    (is (= 1 (get-in producer [:coverage :test-definition-checks :prepared])))
    (is (= [{:ok nil} {:ok nil}] (:body-results consumer)))
    (is (= :passed (get-in consumer [:native-test :status])))
    (is (= 1 (count loads)) (pr-str events))
    (is (seq hits))
    (is (every? #(= (get-in producer [:bundles :packs 0 :id]) (:bundle-id %)) hits))
    (is (empty? misses) (pr-str misses))
    (is (every? #(and (= 4 (count %)) (= "version" (second %)) (= :dir (nth % 2)))
                (:commands consumer)) (pr-str (:commands consumer)))
    (is (<= (count (:commands consumer)) 1))))
