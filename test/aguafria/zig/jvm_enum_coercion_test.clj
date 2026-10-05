(ns aguafria.zig.jvm-enum-coercion-test
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]
            [aguafria.zig.jvm-enum-coercion-fixture :as fixture]
            [aguafria.zig.value :as value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest known-enum-members-retain-checked-coercions
  (let [tag (:ready-now fixture/Tag)
        payload (k/as tag fixture/Payload)]
    (is (value/zig-value? tag))
    (is (= :ready_now (a/value tag)))
    (is (= {:ok nil} (a/value (testing/expectEqual tag payload))))
    (is (= {:ok nil}
           (a/value (testing/expectEqual (:ready-now fixture/Tag)
                                         (k/as :.ready_now fixture/Payload))))))
  (is (= {:ok nil}
         (a/value (testing/expectEqual (:ready-now fixture/Tag)
                                       (k/as (:ready fixture/Constants) fixture/Payload)))))
  (is (= {:ok nil}
         (a/value (testing/expectEqual (:ready-now fixture/Tag)
                                       (k/as (k/as :.ready_now fixture/Tag) fixture/Payload)))))
  (with-open [payload (fixture/Payload {:ratio 0.25})]
    (is (= {:ratio 0.25} (a/value payload)))))

(deftest runtime-enum-tags-do-not-become-constants
  (with-open [tag (k/var (:ready-now fixture/Tag))]
    (is (thrown? clojure.lang.Compiler$CompilerException (k/as tag fixture/Payload))))
  (with-open [tag (fixture/echo-tag (:ready-now fixture/Tag))]
    (is (thrown? clojure.lang.Compiler$CompilerException (k/as tag fixture/Payload))))
  (is (thrown? clojure.lang.Compiler$CompilerException
               (k/as (:changing fixture/Constants) fixture/Payload)))
  (with-open [tag (k/var (:ready fixture/State))]
    (is (= {:ok nil}
           (a/value (testing/expectEqual (:ready fixture/State)
                                         (k/as tag fixture/Flag)))))
    (k/= tag (:waiting fixture/State))
    (is (= :waiting (a/value tag))))
  (is (thrown? clojure.lang.Compiler$CompilerException
               (k/as (:number fixture/Tag) fixture/Payload))))

(deftest live-enum-readers-retain-their-native-schema
  (let [namespace (create-ns (symbol (str "aguafria.jvm.enum-reload-"
                                          (java.util.UUID/randomUUID))))
        evaluate (fn [form] (binding [*ns* namespace] (eval form)))]
    (evaluate '(clojure.core/refer 'clojure.core))
    (evaluate '(require '[aguafria.keyword :as k] '[aguafria.zig :as a]))
    (evaluate '(a/defenum Tag {:type :u8} [:number :ready]))
    (evaluate '(a/defn echo-tag Tag [[tag Tag]] tag))
    (with-open [before (evaluate '(:ready Tag))]
      (with-open [result ((ns-resolve namespace 'echo-tag) before)]
        (is (= :ready (a/value result))))
      (evaluate '(a/defenum Tag {:type :u8} [:number :ratio :ready]))
      (is (= :ready (a/value before)))
      (with-open [result ((ns-resolve namespace 'echo-tag) before)]
        (is (= :ready (a/value result))))
      (with-open [after (evaluate '(:ready Tag))]
        (is (= :ready (a/value after)))
        (is (= 2 (.get ^java.lang.foreign.MemorySegment (a/native-segment after)
                       java.lang.foreign.ValueLayout/JAVA_BYTE 0)))
        (is (= 2 (a/value (k/intFromEnum after))))
        (let [error (try ((ns-resolve namespace 'echo-tag) after) nil
                         (catch clojure.lang.ExceptionInfo error error))]
          (is (= :native-argument-schema (:aguafria/phase (ex-data error))))))
      (evaluate '(a/defn echo-tag Tag [[tag Tag]] tag))
      (with-open [result (evaluate '(echo-tag (:ready Tag)))]
        (is (= :ready (a/value result)))
        (is (= 2 (a/value (k/intFromEnum result))))))))

(deftest enum-transport-keeps-the-full-native-byte-range
  (let [namespace (create-ns (symbol (str "aguafria.jvm.enum-bytes-"
                                          (java.util.UUID/randomUUID))))
        evaluate (fn [form] (binding [*ns* namespace] (eval form)))]
    (evaluate '(clojure.core/refer 'clojure.core))
    (evaluate '(require '[aguafria.keyword :as k] '[aguafria.zig :as a]))
    (doseq [[type-name backing-type members]
            [['ByteTag :u8 [[:low 0] [:high-now 255]]]
             ['WideTag :u16 [[:low 0] [:high-now 1000]]]
             ['SignedTag :i8 [[:low 0] [:high-now -1]]]]]
      (evaluate `(a/defenum ~type-name {:type ~backing-type} ~members))
      (let [function (symbol (str "echo-" type-name))]
        (evaluate `(a/defn ~function ~type-name [[~'tag ~type-name]] ~'tag))
        (with-open [argument (evaluate (list :high-now type-name))
                    result ((ns-resolve namespace function) argument)]
          (is (= :high_now (a/value argument)))
          (is (= :high_now (a/value result)))
          (is (= (second (second members)) (a/value (k/intFromEnum result)))))))))

(defn- enum-coercion-jvm [cache prepare?]
  (let [code `(do
                (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.explain
                         'clojure.java.io)
                (aguafria.zig/configure! {:cache-dir ~cache})
                (if ~prepare?
                  (with-redefs [aguafria.zig.runtime/invoke!
                                (fn [& _#] (throw (ex-info "Preparation invoked native code" {})))
                                aguafria.zig.runtime/invoke-with-result!
                                (fn [& _#] (throw (ex-info "Preparation invoked native code" {})))]
                    (let [report# (aguafria.zig/precompile!
                                   {:analyze ['aguafria.zig.jvm-enum-coercion-fixture]
                                    :report-file ~(str cache "/report.edn")})]
                      (prn {:coverage (:coverage report#)
                            :operations (get-in report# [:analysis 0 :operations])})))
                  (let [events# (atom [])
                        results#
                        (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                          (require 'aguafria.zig.jvm-enum-coercion-fixture)
                          (assert (= :ready_now
                                     (aguafria.zig/value
                                      ((resolve 'aguafria.zig/field)
                                       (var-get (resolve 'aguafria.zig.jvm-enum-coercion-fixture/Tag))
                                       :ready-now))))
                          (with-open [reader# (java.io.PushbackReader.
                                               (clojure.java.io/reader
                                                (clojure.java.io/resource
                                                 "aguafria/zig/jvm_enum_coercion_fixture.clj")))]
                            (let [forms# (doall (take-while some?
                                                            (repeatedly #(read {:eof nil} reader#))))
                                  bodies# (mapcat #(drop 2 %)
                                                  (filter #(and (seq? %) (symbol? (first %))
                                                                (= "deftest" (name (first %))))
                                                          forms#))]
                              (binding [*ns* (the-ns 'aguafria.zig.jvm-enum-coercion-fixture)]
                                (mapv #(aguafria.zig/value (eval %)) bodies#)))))]
                    (prn {:results results# :events @events#})))
                (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Enum-coercion JVM failed" result)))
    (edn/read-string (:out result))))

(deftest compiler-confirmed-enum-members-reuse-one-bundle-after-restart
  (let [cache (str (java.nio.file.Files/createTempDirectory
                    "aguafria-enum-coercion-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (enum-coercion-jvm cache true)
        restarted (enum-coercion-jvm cache false)
        events (:events restarted)
        operations (:operations prepared)
        shadowed (first (filter #(and (= 'aguafria.std.testing/expectEqual (:function %))
                                      (= 'aguafria.zig.jvm-enum-coercion-fixture/Tag
                                         (get-in % [:signatures 0 0]))) operations))]
    (is (= 0 (get-in prepared [:coverage :runtime-candidates :not-fully-prepared])))
    (is (= 0 (get-in prepared [:coverage :handler-records :failed] 0)))
    (is (= 'aguafria.std.testing/expectEqual (:function shadowed)))
    (is (= 'aguafria.zig.jvm-enum-coercion-fixture/Tag
           (get-in shadowed [:signatures 0 0])))
    (is (= 2 (count (filter #(= 'aguafria.zig.jvm-enum-coercion-fixture/echo-tag
                                (:function %)) operations))))
    (is (= (vec (repeat 7 {:ok nil})) (:results restarted)))
    (is (seq (filter #(= :bundle-cache-hit (:event %)) events)))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))) (pr-str events))
    (is (empty? (filter #(#{:compiled :compile-failed} (:event %)) events)) (pr-str events))
    (is (empty? (filter #(and (= :disk-cache-hit (:event %))
                              (str/starts-with? (or (:module %) "") "aguafria.jvm.")) events))
        (pr-str events))))
