(ns aguafria.zig.comptime-macro-precompile-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(defn- worker [cache prepare?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.zig.precompile 'aguafria.zig.runtime
                    'aguafria.zig.explain 'aguafria.zig.compiler-work)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (if ~prepare?
             (let [fail!# (fn [& _#] (throw (ex-info "Preparation invoked application code" {})))
                   report# (with-redefs [aguafria.zig.runtime/invoke! fail!#
                                         aguafria.zig.runtime/invoke-with-result! fail!#]
                             (aguafria.zig.precompile/precompile!
                              {:analyze ['aguafria.zig.comptime-macro-fixture]
                               :parallelism 16 :report-file ~(str cache "/report.edn")}))]
               (prn {:coverage (:coverage report#)}))
             (do
               (require 'aguafria.zig.comptime-macro-fixture)
               (let [events# (atom []) work# (aguafria.zig.compiler-work/collector)
                     result#
                     (binding [*ns* (the-ns 'aguafria.zig.comptime-macro-fixture)
                               aguafria.zig.explain/*reporter* #(swap! events# conj %)
                               aguafria.zig.compiler-work/*collector* work#
                               aguafria.zig.compiler-work/*compiler-command-policy*
                               (fn [command#]
                                 (throw (ex-info "Evaluation attempted compilation" {:command command#})))]
                       (eval '~'(a/value (k/comptime (phase-answer 41)))))]
                 (prn {:value result# :events @events#
                       :work (aguafria.zig.compiler-work/report work#)}))))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "-Xmx768m" "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (spit (io/file cache (str (if prepare? "producer" "consumer") "-process.edn")) (pr-str result))
    (when-not (zero? (:exit result))
      (throw (ex-info "Comptime preparation worker failed" result)))
    (edn/read-string (:out result))))

(deftest comptime-uses-prepared-native-handlers-in-a-fresh-process
  (let [cache (str (Files/createTempDirectory "aguafria-comptime-macro-"
                                              (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (worker cache true)
        consumer (worker cache false)
        events (:events consumer)]
    (is (= 42 (:value consumer)))
    (is (zero? (get-in consumer [:work :compiler-invocations])))
    (is (seq (filter #(= :bundle-cache-hit (:event %)) events)))
    (is (empty? (filter #(#{:compiled :compile-failed :disk-cache-hit} (:event %)) events))
        (pr-str events))))
