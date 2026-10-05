(ns aguafria.zig.jvm-type-source-context-test
  (:require [aguafria.zig :as a]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(def ^:private pointer-capture-type
  '(aguafria.keyword/TypeOf
    (block
     (let [items (aguafria.keyword/var (aguafria.zig/array [1 2] :u8))]
       (aguafria.keyword/for
        [(aguafria.keyword/* item) (aguafria.keyword/& items)]
        (aguafria.keyword/= (deref item) 0))))))

(defn- logged-proof [value]
  (let [message (str "aguafria.type-source:0:" value)
        encoded (.formatHex (java.util.HexFormat/of)
                            (.getBytes message java.nio.charset.StandardCharsets/UTF_8))]
    {:exit 1 :err (str "error: found compile log statement\nCompile Log Output:\n"
                      "\"aguafria.operation.hex:" encoded "\"\n")}))

(deftest retained-type-proof-emits-in-its-caller-context
  (let [context (the-ns 'aguafria.zig.jvm-type-source-context-test)
        sources (atom [])]
    (with-redefs [runtime/registered-declarations (constantly [])
                  runtime/inspect-module!
                  (fn [module supplier]
                    (swap! sources conj [module (:source (supplier []))])
                    (logged-proof true))]
      (is (true? (#'jvm/confirm-type-argument-source! context :void pointer-capture-type))))
    (is (= [(str (ns-name context))] (mapv first @sources)))
    (is (str/includes? (second (first @sources)) "|*item|"))))

(deftest detached-lexical-spellings-do-not-become-type-operands
  (let [context (create-ns (symbol (str "aguafria.detached-type-source-" (random-uuid))))]
    (try
      (binding [*ns* context]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (refer 'aguafria.zig :only '[array slice]))
      (doseq [name '[a array slice]]
        (is (nil? (#'jvm/closed-type-argument-source
                   context (list 'aguafria.keyword/TypeOf name)))))
      (is (some? (#'jvm/closed-type-argument-source context pointer-capture-type)))
      (let [calls (atom [])
            call {:function 'aguafria.zig/type :caller (ns-name context)
                  :args [{:comptime-type :u8}]
                  :source-arguments ['(aguafria.keyword/TypeOf 1)]}]
        (with-redefs-fn
          {#'jvm/precompile-call-base! #(do (swap! calls conj %) {:status :prepared})
           #'jvm/confirm-type-argument-source! (constantly false)}
          #(is (= :prepared (:status (jvm/precompile-call! call)))))
        (is (= [call] @calls)))
      (finally (remove-ns (ns-name context))))))

(deftest native-type-proof-checks-pointer-captures-without-executing-them
  (let [context (create-ns (symbol (str "aguafria.type-source-context-" (random-uuid))))
        module (str (ns-name context))
        foreign (create-ns (symbol (str "aguafria.foreign-type-source-" (random-uuid))))
        foreign-module (str (ns-name foreign))
        registry @#'runtime/registry
        cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "type-source-context-" (make-array java.nio.file.attribute.FileAttribute 0)))
        results (atom [])
        inspect runtime/inspect-module!]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (eval '(a/defconst anchor :u8 0)))
      (binding [*ns* foreign runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (eval '(a/defconst ByteType (a/type :u8))))
      (binding [runtime/*compile-only?* true]
        (runtime/call-with-precompile-configuration
         (assoc (runtime/configuration) :cache-dir cache)
         #(with-redefs [runtime/invoke! (fn [& _] (throw (ex-info "Type proof invoked native code" {})))
                        runtime/invoke-with-result! (fn [& _] (throw (ex-info "Type proof invoked native code" {})))
                        runtime/inspect-module!
                        (fn [& arguments]
                          (let [result (apply inspect arguments)]
                            (swap! results conj result)
                            result))]
            (is (true? (#'jvm/confirm-type-argument-source! context :void pointer-capture-type)))
            (is (false? (#'jvm/confirm-type-argument-source! context :u8 :u16)))
            (is (true? (#'jvm/confirm-type-argument-source!
                        context (symbol foreign-module "ByteType") :u8))))))
      (is (= 3 (count @results)))
      (is (every? #(str/includes? (:err %) "found compile log statement") @results)
          (pr-str @results))
      (is (some #(str/starts-with? % (str "-M" foreign-module "="))
                (:command (last @results))))
      (finally
        (swap! registry dissoc module foreign-module)
        (remove-ns (ns-name context))
        (remove-ns (ns-name foreign))))))
