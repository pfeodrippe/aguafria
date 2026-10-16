(ns aguafria.zig.precompile-field-type-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]])
  (:import [java.nio ByteOrder]
           [java.nio.file Files]))

(defn- endian-worker [cache prepare?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.zig.precompile 'aguafria.zig.runtime
                    'aguafria.zig.explain 'aguafria.zig.compiler-work)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (if ~prepare?
             (let [fail!# (fn [& _#] (throw (ex-info "Preparation invoked native code" {})))
                   report# (with-redefs [aguafria.zig.runtime/invoke! fail!#
                                         aguafria.zig.runtime/invoke-with-result! fail!#]
                             (aguafria.zig.precompile/precompile!
                              {:analyze ['learn.example.test-pointer-casting]
                               :parallelism 16 :report-file ~(str cache "/report.edn")}))
                   analysis# (first (:analysis report#))]
               (prn {:refinement (select-keys (:jvm-value-representation-refinement analysis#)
                                              [:basis :compiler-errors? :sources])
                     :coverage (:coverage report#)}))
             (do
               (require 'learn.example.test-pointer-casting)
               (let [events# (atom [])
                     work# (aguafria.zig.compiler-work/collector)
                     result#
                     (binding [*ns* (the-ns 'learn.example.test-pointer-casting)
                               aguafria.zig.explain/*reporter* #(swap! events# conj %)
                               aguafria.zig.compiler-work/*collector* work#
                               aguafria.zig.compiler-work/*compiler-command-policy*
                               (fn [command#]
                                 (throw (ex-info "Evaluation attempted compilation" {:command command#})))]
                       ;; Evaluate the actual initializer, not the prepared constant reader.
                       (aguafria.zig/value (eval '~'((:endian (:arch (:cpu builtin/target)))))))]
                 (prn {:value result# :events @events#
                       :work (aguafria.zig.compiler-work/report work#)}))))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "-Xmx768m" "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))
        phase (if prepare? "producer" "consumer")]
    (spit (io/file cache (str phase "-process.edn")) (pr-str result))
    (when-not (zero? (:exit result))
      (throw (ex-info "Field-type preparation worker failed" result)))
    (edn/read-string (:out result))))

(deftest comptime-field-reflection-does-not-discard-runtime-field-preparation
  (let [cache (str (Files/createTempDirectory "aguafria-field-type-"
                                              (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (endian-worker cache true)
        consumer (endian-worker cache false)
        events (:events consumer)]
    (is (= :zig-compiler (get-in producer [:refinement :basis])))
    (is (false? (get-in producer [:refinement :compiler-errors?])))
    (is (seq (get-in producer [:refinement :sources])))
    (is (= (if (= ByteOrder/LITTLE_ENDIAN (ByteOrder/nativeOrder)) :little :big)
           (:value consumer)))
    (is (zero? (get-in consumer [:work :compiler-invocations])))
    (is (seq (filter #(= :bundle-cache-hit (:event %)) events)))
    (is (empty? (filter #(#{:compiled :compile-failed :disk-cache-hit} (:event %)) events))
        (pr-str events))))
