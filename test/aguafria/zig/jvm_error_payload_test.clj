(ns aguafria.zig.jvm-error-payload-test
  (:require [aguafria.keyword :as k]
            [aguafria.std.heap :as heap]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]
            [aguafria.zig.jvm-error-payload-fixture :as fixture]
            [aguafria.zig.value :as value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest native-error-payloads-retain-type-and-value-semantics
  (with-open [source (k/var {:ok 41} [:error-union :anyerror :i32])
              payload (k/try source)]
    (is (value/zig-value? payload))
    (is (= :i32 (value/qualified-type payload)))
    (k/= source {:ok 42})
    (is (= 41 (a/value payload)))
    (is (= 42 (a/value (k/try source)))))
  (with-open [optional (k/as 7 [:error-union :anyerror [:optional :i32]])
              payload (k/try optional)]
    (is (= [:optional :i32] (value/qualified-type payload)))
    (is (= 7 (a/value (a/unwrap payload)))))
  (with-open [empty (k/as [] [:error-union :anyerror [:array 0 :u8]])
              payload (k/try empty)]
    (is (= [:array 0 :u8] (value/qualified-type payload)))
    (is (= [] (a/value payload))))
  (with-open [source (k/as {:ok {:ok 7}}
                           [:error-union :anyerror [:error-union :anyerror :i32]])
              inner (k/try source)
              payload (k/try inner)]
    (is (= [:error-union :anyerror :i32] (value/qualified-type inner)))
    (is (= 7 (a/value payload))))
  (is (nil? (k/try (k/as {:ok nil} [:error-union :anyerror :void]))))
  (is (nil? (k/try (testing/expectEqual 1 1)))))

(deftest generic-error-union-results-retain-native-payloads
  (let [number (k/try (fixture/generic-number 40))
        optional (k/try (fixture/generic-optional 42))
        missing (k/try (fixture/generic-optional nil))
        packet (k/try (fixture/generic-packet 43))]
    (is (value/zig-value? number))
    (is (= :i32 (value/qualified-type number)))
    (is (= 41 (a/value number)))
    (is (value/zig-value? optional))
    (is (= 42 (a/value (a/unwrap optional))))
    (is (= nil (a/value missing)))
    (is (value/zig-value? packet))
    (is (= 43 (a/value (:number packet))))))

(deftest native-errors-become-named-jvm-exceptions
  (doseq [operation [#(k/try (fixture/generic-number -1))
                     #(k/try (k/as (:Rejected fixture/Failure)
                                   [:error-union fixture/Failure :i32]))]]
    (let [error (try (operation) nil
                     (catch clojure.lang.ExceptionInfo error error))]
      (is (= :native-error (:aguafria/phase (ex-data error))))
      (is (= :Rejected (:error-name (ex-data error))))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires a native Zig error union"
                        (k/try (k/i32 1))))
  (is (= "try value" (a/emit-expr '(k/try value)))))

(deftest constructed-error-unions-retain-native-storage
  (let [schema [:error-union 'aguafria.zig.jvm-error-payload-fixture/Failure :i32]]
    (with-open [result (k/as (:Rejected fixture/Failure) schema)]
      (is (value/zig-value? result))
      (is (= [:error-union [:error-set [:Rejected]] :i32] (value/qualified-type result)))
      (is (nil? (k/try (testing/expectError (:Rejected fixture/Failure) result)))))
    (with-open [result (k/var (k/as (:Rejected fixture/Failure) schema))]
      (is (value/zig-value? result))
      (is (nil? (k/try (testing/expectError (:Rejected fixture/Failure) result))))
      (k/= result 42)
      (is (= 42 (a/value (k/try result)))))))

(deftest native-string-and-container-members-compose
  (is (= [104 101 108 108 111] (a/value (a/deref "hello"))))
  (let [sentinel (k/as "hello" [:* {:sentinel 0 :size :slice :const? true} :u8])
        pointer (k/as sentinel [:sentinel-const :u8 0])]
    (is (= 5 (a/value (:len sentinel))))
    (is (= 111 (a/value (a/get pointer 4))))
    (is (= 0 (a/value (a/get pointer 5)))))
  (is (value/zig-type? heap/FixedBufferAllocator))
  (with-open [buffer (k/var k/undefined [:array 100 :u8])]
    (let [initial ((:init heap/FixedBufferAllocator) (k/& buffer))
          allocator (k/var initial)
          interface ((:allocator allocator))
          result (k/try ((:alloc interface) :u8 3))]
      (is (= 'aguafria.std.heap/FixedBufferAllocator (value/qualified-type initial)))
      (is (nil? (k/try (testing/expectEqual (a/type heap/FixedBufferAllocator)
                                            (k/TypeOf initial)))))
      (is (nil? (k/try (testing/expectEqual (a/type aguafria.std.mem/Allocator)
                                            (k/TypeOf interface)))))
      (is (= 3 (a/value (:len result)))))))

(defn- payload-jvm [cache prepare? fixture]
  (let [resource (str (str/replace (str fixture) "." "/") ".clj")
        resource (str/replace resource "-" "_")
        code `(do
                (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.explain
                         'clojure.java.io)
                (aguafria.zig/configure! {:cache-dir ~cache})
                (if ~prepare?
                  (with-redefs [aguafria.zig.runtime/invoke!
                                (fn [& _#] (throw (ex-info "Preparation invoked native code" {})))
                                aguafria.zig.runtime/invoke-with-result!
                                (fn [& _#] (throw (ex-info "Preparation invoked native code" {})))]
                    (let [report# (aguafria.zig/precompile!
                                   {:analyze ['~fixture]
                                    :report-file ~(str cache "/report.edn")})]
                      (prn {:coverage (:coverage report#)
                            :operations (get-in report# [:analysis 0 :operations])})))
                  (let [events# (atom [])
                        results#
                        (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                          (require '~fixture)
                          (with-open [reader# (java.io.PushbackReader.
                                               (clojure.java.io/reader
                                                (clojure.java.io/resource
                                                 ~resource)))]
                            (let [forms# (doall (take-while some?
                                                            (repeatedly #(read {:eof nil} reader#))))
                                  bodies# (mapcat #(drop 2 %)
                                                  (filter #(and (seq? %) (symbol? (first %))
                                                                (= "deftest" (name (first %)))) forms#))]
                              (binding [*ns* (the-ns '~fixture)]
                                (mapv #(aguafria.zig/value (eval %)) bodies#)))))]
                    (prn {:results results# :events @events#})))
                (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Error-payload JVM failed" result)))
    (edn/read-string (:out result))))

(deftest scoped-native-captures-reuse-one-bundle-after-restart
  (let [cache (str (java.nio.file.Files/createTempDirectory
                    "aguafria-scoped-captures-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        fixture 'aguafria.zig.jvm-scoped-capture-fixture
        prepared (payload-jvm cache true fixture)
        restarted (payload-jvm cache false fixture)
        events (:events restarted)]
    (is (zero? (get-in prepared [:coverage :runtime-candidates :not-fully-prepared]))
        (pr-str (:operations prepared)))
    (is (zero? (get-in prepared [:coverage :handler-records :failed] 0)))
    (is (= [{:ok nil} {:ok nil} {:ok nil}] (:results restarted)))
    (is (seq (filter #(= :bundle-cache-hit (:event %)) events)))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))) (pr-str events))
    (is (empty? (filter #(#{:compiled :compile-failed} (:event %)) events)) (pr-str events))
    (is (empty? (filter #(and (= :disk-cache-hit (:event %))
                              (str/starts-with? (or (:module %) "") "aguafria.jvm.")) events))
        (pr-str events))))

(deftest reflected-runtime-types-reuse-one-bundle-after-restart
  (let [cache (str (java.nio.file.Files/createTempDirectory
                    "aguafria-runtime-reflection-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        fixture 'aguafria.zig.discovery-runtime-reflection-fixture
        prepared (payload-jvm cache true fixture)
        restarted (payload-jvm cache false fixture)
        events (:events restarted)]
    (is (zero? (get-in prepared [:coverage :runtime-candidates :not-fully-prepared])))
    (is (zero? (get-in prepared [:coverage :handler-records :failed] 0)))
    (is (= [{:ok nil} {:ok nil}] (:results restarted)))
    (is (seq (filter #(= :bundle-cache-hit (:event %)) events)))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))) (pr-str events))
    (is (empty? (filter #(#{:compiled :compile-failed} (:event %)) events)) (pr-str events))
    (is (empty? (filter #(and (= :disk-cache-hit (:event %))
                              (str/starts-with? (or (:module %) "") "aguafria.jvm.")) events))
        (pr-str events))))

(deftest inferred-native-parameters-reuse-one-bundle-after-restart
  (let [cache (str (java.nio.file.Files/createTempDirectory
                    "aguafria-native-parameters-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        fixture 'aguafria.zig.jvm-native-parameter-fixture
        prepared (payload-jvm cache true fixture)
        restarted (payload-jvm cache false fixture)
        events (:events restarted)]
    (is (zero? (get-in prepared [:coverage :runtime-candidates :not-fully-prepared])))
    (is (zero? (get-in prepared [:coverage :handler-records :failed] 0)))
    (is (= [{:ok nil}] (:results restarted)))
    (is (seq (filter #(= :bundle-cache-hit (:event %)) events)))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))) (pr-str events))
    (is (empty? (filter #(#{:compiled :compile-failed} (:event %)) events)) (pr-str events))
    (is (empty? (filter #(and (= :disk-cache-hit (:event %))
                              (str/starts-with? (or (:module %) "") "aguafria.jvm.")) events))
        (pr-str events))))

(deftest native-error-payloads-reuse-one-bundle-after-restart
  (let [cache (str (java.nio.file.Files/createTempDirectory
                    "aguafria-error-payload-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        fixture 'aguafria.zig.jvm-error-payload-fixture
        prepared (payload-jvm cache true fixture)
        restarted (payload-jvm cache false fixture)
        events (:events restarted)
        operations (:operations prepared)
        tries (filter #(= 'aguafria.keyword/try (:function %)) operations)]
    (is (seq tries))
    (is (every? #(= :observed (:status %)) tries))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers tries)))
    (is (= [nil nil nil nil nil nil] (:results restarted)))
    (is (empty? (filter #(#{:compiled :compile-failed} (:event %)) events)))
    (is (empty? (filter #(and (= :disk-cache-hit (:event %))
                              (str/starts-with? (:module %) "aguafria.jvm.")) events)))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))))
    (is (= 1 (count (set (keep :bundle-id events)))))))

(deftest string-storage-reuses-one-bundle-after-restart
  (let [cache (str (java.nio.file.Files/createTempDirectory
                    "aguafria-string-storage-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        fixture 'aguafria.zig.jvm-string-storage-fixture
        prepared (payload-jvm cache true fixture)
        restarted (payload-jvm cache false fixture)
        events (:events restarted)]
    (is (pos? (get-in prepared [:coverage :runtime-candidates :fully-prepared] 0)))
    (is (= [nil nil nil] (:results restarted)))
    (is (empty? (filter #(#{:compiled :compile-failed} (:event %)) events)))
    (is (empty? (filter #(and (= :disk-cache-hit (:event %))
                              (str/starts-with? (:module %) "aguafria.jvm.")) events)))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))))
    (is (= 1 (count (set (keep :bundle-id events)))))))

(deftest sentinel-slices-retain-storage-and-reuse-one-bundle-after-restart
  (let [cache (str (java.nio.file.Files/createTempDirectory
                    "aguafria-sentinel-slice-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        fixture 'aguafria.zig.jvm-sentinel-slice-fixture
        prepared (payload-jvm cache true fixture)
        restarted (payload-jvm cache false fixture)
        operations (:operations prepared)
        slices (filter #(= :slice-sentinel (:storage-kind %)) operations)
        events (:events restarted)]
    (is (= 2 (count slices)))
    (is (every? #(= :observed (:status %)) slices))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers slices)))
    (is (= [nil nil] (:results restarted)))
    (is (empty? (filter #(#{:compiled :compile-failed} (:event %)) events)))
    (is (empty? (filter #(and (= :disk-cache-hit (:event %))
                              (str/starts-with? (:module %) "aguafria.jvm.")) events)))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))))
    (is (= 1 (count (set (keep :bundle-id events)))))))
