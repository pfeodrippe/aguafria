(ns aguafria.zig.comptime-source-operand-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.value :as value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest retained-source-type-operands-follow-native-contracts
  (let [context (the-ns 'aguafria.zig.comptime-source-operand-test)
        canonical (var jvm/canonical-comptime-source-operands)]
    (is (= [{:name 'T :type :type :properties {:zig/prefix "comptime"}}]
           (jvm/call-parameters (meta #'k/typeInfo) 1)))
    (is (= ['expression :type]
           (mapv :type (jvm/call-parameters (meta #'k/as) 2))))
    (doseq [[source expected]
            [['(k/typeInfo [:* :u32]) '(aguafria.keyword/typeInfo (type [:* :u32]))]
             ['(k/sizeOf [:array 4 :u8]) '(aguafria.keyword/sizeOf (type [:array 4 :u8]))]
             ['(k/typeInfo (a/type [:* :u32])) '(aguafria.keyword/typeInfo (type [:* :u32]))]
             ['(a/field (k/typeInfo [:optional :u64]) :optional)
              '(field (aguafria.keyword/typeInfo (type [:optional :u64])) :optional)]
             ['(k/as 1 :u1) '(aguafria.keyword/as 1 :u1)]
             ['(k/as [1 2] [:array 2 :i32])
              '(aguafria.keyword/as [1 2] (type [:array 2 :i32]))]
             ['(k/as (k/typeInfo [:optional :u64]) :type)
              '(aguafria.keyword/as (aguafria.keyword/typeInfo (type [:optional :u64])) :type)]]]
      (let [qualified (emitter/qualify-form context source)
            result (canonical qualified)]
        (is (= expected result))
        (is (= result (canonical result)))
        (is (= (emitter/emit-expr context qualified) (emitter/emit-expr context result)))))
    (let [value-source (emitter/qualify-form context '(k/TypeOf [1 2]))
          quoted '(quote (aguafria.keyword/typeInfo [:* :u32]))
          marked (with-meta '(aguafria.keyword/typeInfo [:* :u32]) {:retained-proof :actual-source})]
      (is (= value-source (canonical value-source)))
      (is (= quoted (canonical quoted)))
      (is (= (meta marked) (meta (canonical marked)))))))

(deftest prepared-and-public-projection-plans-have-identical-identity
  (let [captured (atom [])]
    (with-redefs-fn
      {#'jvm/precompile-expression! #(swap! captured conj %)}
      #(doseq [[source member]
               [['(aguafria.keyword/typeInfo [:* :u32]) :pointer]
                ['(aguafria.zig/field (aguafria.keyword/typeInfo [:* :u32]) :pointer) :child]]]
         (jvm/precompile-storage! {:kind :field :receiver {:comptime-expression source}
                                   :member member :indices []})))
    (doseq [[adapter expression]
            (map vector @captured
                 ['(aguafria.keyword/typeInfo (type [:* :u32]))
                  '(field (aguafria.keyword/typeInfo (type [:* :u32])) :pointer)])]
      (let [member (last (:expression adapter))
            context (:context adapter)
            expression ((var jvm/comptime-field-expression) expression member)
            public-plan ((var jvm/prepare-expression!) context expression [] 'comptimeExpressionResult)]
        (is (= (:function adapter) (:function public-plan)))
        (is (= (:expression adapter) (:expression public-plan)))
        (is (= (:adapter-key adapter) (:adapter-key public-plan)))))))

(deftest typed-errors-use-the-same-operand-plan-before-and-after-preparation
  (let [declarations [{:type :anytype}]
        lift (var jvm/call-inputs)
        prepare (var jvm/prepared-call-inputs)]
    (doseq [name [:Left :Right]
            :let [type [:error-set [name]]
                  error (value/->ZigError (clojure.core/name name) type)
                  expression (value/error-form error)
                  ordinary (lift declarations [error])
                  prepared (prepare [{:comptime-expression expression}] declarations)]]
      (is (= (:expression-arguments ordinary) (:expression-arguments prepared)))
      (is (= (:parameters ordinary) (:parameters prepared)))
      (is (= (emitter/emit-expr expression)
             (emitter/emit-expr (first (:expression-arguments ordinary))))))))

(defn- projection-jvm [cache producer?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.precompile
                    'aguafria.zig.explain 'clojure.java.io 'clojure.java.shell)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (if ~producer?
             (let [fail!# (fn [& _#] (throw (ex-info "Preparation invoked native code" {})))
                   report# (with-redefs [aguafria.zig.runtime/invoke! fail!#
                                         aguafria.zig.runtime/invoke-with-result! fail!#]
                             (aguafria.zig.precompile/precompile!
                              {:analyze ['learn.example.test-pointer-casting
                                         'aguafria.zig.comptime-source-operand-fixture]
                               :parallelism 1 :report-file ~(str cache "/report.edn")}))]
               (prn {:coverage (:coverage report#) :bundles (:bundles report#)
                     :projections (vec (for [entry# (:analysis report#)
                                             op# (:operations entry#)
                                             :when (and (= 'aguafria.zig/field (:function op#))
                                                        (contains? #{(symbol "pointer-child-type")
                                                                     (symbol "optional-child")}
                                                                   (:declaration-name op#)))]
                                         (assoc (select-keys op# [:id :form :signatures :handlers])
                                                :namespace (:namespace entry#))))}))
             (let [events# (atom []) commands# (atom []) original-shell# clojure.java.shell/sh
                   body-round#
                   (fn []
                     (vec (for [[namespace# resource#]
                                [['learn.example.test-pointer-casting "learn/example/test_pointer_casting.clj"]
                                 ['aguafria.zig.comptime-source-operand-fixture
                                  "aguafria/zig/comptime_source_operand_fixture.clj"]]
                                :let [forms# (with-open [reader# (java.io.PushbackReader.
                                                                  (clojure.java.io/reader
                                                                   (clojure.java.io/resource resource#)))]
                                               (binding [*read-eval* false]
                                                 (into [] (take-while some?)
                                                       (repeatedly #(read {:eof nil} reader#)))))]
                                form# forms#
                                :when (and (seq? form#) (= (symbol "a/deftest") (first form#)))]
                            (binding [*ns* (the-ns namespace#)]
                              {:namespace namespace# :name (second form#)
                               :result (aguafria.zig/value
                                        (eval (cons (symbol "do") (drop 2 form#))))}))))
                   output#
                   (with-redefs [clojure.java.shell/sh
                                 (fn [& args#]
                                   (swap! commands# conj (vec args#))
                                   (apply original-shell# args#))]
                     (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                       (require 'learn.example.test-pointer-casting
                                'aguafria.zig.comptime-source-operand-fixture)
                       (let [first-round# (body-round#)
                             cold-commands# @commands#
                             _# (reset! commands# [])
                             warm-round# (body-round#)]
                         {:results [first-round# warm-round#]
                          :bootstrap-commands cold-commands# :warm-commands @commands#})))]
               (prn (assoc output# :events @events#))))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED" "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result)) (throw (ex-info "Comptime projection JVM failed" result)))
    (edn/read-string (:out result))))

(deftest compiler-prepared-projections-reuse-the-pack-in-a-fresh-public-consumer
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "comptime-projections-" (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (projection-jvm cache true)
        _ (spit (io/file cache "producer.edn") (pr-str producer))
        consumer (projection-jvm cache false)
        _ (spit (io/file cache "consumer.edn") (pr-str consumer))
        events (:events consumer)
        hits (filter #(= :bundle-cache-hit (:event %)) events)
        misses (filter #(or (#{:compiled :compile-failed} (:event %))
                            (and (= :disk-cache-hit (:event %)) (nil? (:bundle-id %))
                                 (str/starts-with? (str (:module %)) "aguafria.jvm."))) events)
        commands (:bootstrap-commands consumer)
        bootstrap? (fn [command]
                     (or (and (= 4 (count command)) (= "version" (second command))
                              (= :dir (nth command 2)))
                         ;; This prints the compiler's builtin module; it does
                         ;; not compile an image or semantically inspect a body.
                         (and (= 3 (count command))
                              (= ["build-lib" "--show-builtin"] (subvec command 1)))))]
    (is (= 0 (get-in producer [:coverage :namespaces :baseline-failures])))
    (is (= 4 (count (:projections producer))))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers (:projections producer))))
    (is (= (repeat 2 '[pointer-casting pointer-child-type optional-child array-layout])
           (map #(mapv :name %) (:results consumer))))
    (is (every? #(= {:ok nil} (:result %)) (apply concat (:results consumer)))
        (pr-str (:results consumer)))
    (is (empty? misses) (pr-str misses))
    (is (seq hits))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))))
    (is (every? #(= (get-in producer [:bundles :packs 0 :id]) (:bundle-id %)) hits))
    (is (every? bootstrap? commands) (pr-str commands))
    (is (<= (count (filter #(= "version" (second %)) commands)) 1))
    (is (<= (count (filter #(= "--show-builtin" (nth % 2 nil)) commands)) 1))
    (is (empty? (:warm-commands consumer)) (pr-str (:warm-commands consumer)))))
