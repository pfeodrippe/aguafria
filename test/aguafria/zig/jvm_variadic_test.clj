(ns aguafria.zig.jvm-variadic-test
  (:require [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]))

(deftest variadic-parameters-require-specialization
  (is (runtime/generic-function-argument?
       {:name '... :type '_ :properties {:zig/variadic true}}))
  (is (runtime/generic-function-argument? {:type :anytype}))
  (is (runtime/generic-function-argument? {:type :u32 :properties {:zig/prefix "comptime"}}))
  (is (not (runtime/generic-function-argument? {:type :c_int}))))

(defn- variadic-jvm [cache producer?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.keyword 'aguafria.std.c
                    'aguafria.zig.runtime 'aguafria.zig.precompile
                    'aguafria.zig.explain)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (if ~producer?
             (let [fail!# (fn [& _#] (throw (ex-info "Preparation invoked native code" {})))
                   report# (with-redefs [aguafria.zig.runtime/invoke! fail!#
                                         aguafria.zig.runtime/invoke-with-result! fail!#]
                             (aguafria.zig.precompile/precompile!
                              {:namespaces ['aguafria.zig.jvm-variadic-fixture]
                               :analyze ['aguafria.zig.jvm-variadic-fixture]
                               :parallelism 1}))]
               (prn {:bundles (:bundles report#) :coverage (:coverage report#)
                     :operations (mapv #(select-keys % [:function :form :status :reason :handlers])
                                       (mapcat :operations (:analysis report#)))}))
             (do
               (require 'aguafria.zig.jvm-variadic-fixture)
               (let [add# (resolve 'aguafria.zig.jvm-variadic-fixture/add)
                     echo-C# (resolve 'aguafria.zig.jvm-variadic-fixture/echo-C)
                     events# (atom [])
                     output# (java.io.StringWriter.)
                     values# (binding [*out* output#
                                       aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                               [(aguafria.zig/value (add# 0))
                                (aguafria.zig/value
                                 (add# 1 (aguafria.keyword/as 1 :c_int)))
                                (aguafria.zig/value
                                 (add# 2 (aguafria.keyword/as 1 :c_int) (aguafria.keyword/as 2 :c_int)))
                                (aguafria.zig/value (aguafria.std.c/printf ""))
                                (aguafria.zig/value (aguafria.std.c/printf "%d\n" (aguafria.keyword/i32 12)))
                                (aguafria.zig/value
                                 (aguafria.std.c/printf "%s=%d\n" "value" (aguafria.keyword/i32 42)))
                                (aguafria.zig/value (echo-C# :c_short 12))
                                (aguafria.zig/value (echo-C# :c_long 42))
                                (aguafria.zig/value (echo-C# :c_ulonglong 99))])]
                 (prn {:values values# :output (str output#) :events @events#}))))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Variadic JVM failed" result)))
    (edn/read-string (:out result))))

(deftest fresh-variadic-calls-reuse-the-compiler-prepared-pack
  (let [cache (str (java.nio.file.Files/createTempDirectory
                   (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                   "variadic-" (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (variadic-jvm cache true)
        consumer (variadic-jvm cache false)
        operations (:operations producer)
        calls (filter #(contains? #{'aguafria.zig.jvm-variadic-fixture/add
                                   'aguafria.zig.jvm-variadic-fixture/echo-C
                                   'aguafria.std.c/printf}
                                   (:function %)) operations)
        frame-starts (filter #(= 'aguafria.keyword/cVaStart (:function %)) operations)
        events (:events consumer)
        hits (filter #(= :bundle-cache-hit (:event %)) events)
        loads (filter #(= :bundle-loaded (:event %)) events)
        misses (filter #(#{:compiled :compile-failed :disk-cache-hit} (:event %)) events)]
    (spit (io/file cache "producer.edn") (pr-str producer))
    (spit (io/file cache "consumer.edn") (pr-str consumer))
    (is (= 0 (get-in producer [:coverage :namespaces :baseline-failures])))
    (is (not-any? #(= :failed (:status %)) (mapcat :handlers operations)))
    (is (= 9 (count calls)))
    (is (every? #(and (seq (:handlers %)) (every? (fn [h] (= :prepared (:status h))) (:handlers %))) calls))
    (is (= 1 (count frame-starts)))
    (is (every? #(and (= :unsupported (:status %))
                     (= :variadic-frame-required (:reason %))
                     (empty? (:handlers %))) frame-starts))
    (is (= [0 1 3 0 3 9 12 42 99] (:values consumer)))
    (is (= "12\nvalue=42\n" (:output consumer)))
    (is (empty? misses) (pr-str misses))
    (is (seq hits))
    (is (= 1 (count loads)))
    (is (every? #(= (get-in producer [:bundles :packs 0 :id]) (:bundle-id %)) hits))))
