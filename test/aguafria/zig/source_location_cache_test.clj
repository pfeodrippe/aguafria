(ns aguafria.zig.source-location-cache-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(defn- run-jvm [cache mode filename padding]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.precompile
                    'aguafria.zig.compiler-work 'aguafria.zig.repl
                    'aguafria.zig.explain 'clojure.java.io 'clojure.string)
           (aguafria.zig/configure! {:cache-dir ~cache})
           (if (= ~mode :prepare)
             (let [report# (aguafria.zig.precompile/precompile!
                            {:analyze ['aguafria.zig.source-location-fixture]
                             :parallelism 1 :report-file ~(str cache "/report.edn")})]
               (assert (empty? (get-in report# [:namespace-admission :rejected-namespaces]))
                       (pr-str (:rejected-preparation report#)))
               (prn {:bundles (:bundles report#)}))
             (let [raw# (slurp (clojure.java.io/resource "aguafria/zig/source_location_fixture.clj"))
                   source# (str ~padding (if (seq ~padding)
                                           (clojure.string/replace raw# "\n(a/" "\n      (a/") raw#))
                   events# (atom [])
                   work# (aguafria.zig.compiler-work/collector)
                   run# aguafria.zig.compiler-work/run-command!]
               (when (seq ~padding)
                 (clojure.java.io/make-parents ~filename)
                 (spit ~filename source#))
               (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)
                         aguafria.zig.compiler-work/*collector* work#]
                 (with-redefs [aguafria.zig.compiler-work/run-command!
                               (fn [command# invoke#]
                                 (when (= :compiler ((ns-resolve 'aguafria.zig.compiler-work (symbol "command-kind")) command#))
                                   (throw (ex-info "Relocated source attempted compiler work" {:command command#})))
                                 (run# command# invoke#))]
                   (clojure.lang.Compiler/load (java.io.StringReader. source#) ~filename ~filename)
                   (binding [*ns* (the-ns 'aguafria.zig.source-location-fixture) *file* ~filename]
                     (doseq [row# (:rows (aguafria.zig.repl/plan source# "aguafria.zig.source-location-fixture"))]
                       (let [result# (aguafria.zig/value (eval (read-string (:expression row#))))]
                         (if (= :initializer (:kind row#))
                           (assert (= (aguafria.zig/value
                                       (var-get (ns-resolve *ns* (symbol (:declaration row#)))))
                                      result#))
                           (assert (= {:ok nil} result#))))))))
               (prn {:events @events# :compiler-work (aguafria.zig.compiler-work/report work#)})))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED" "-Xmx1024m" "-XX:ActiveProcessorCount=2"
                         "-cp" (System/getProperty "java.class.path") "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Source location regression JVM failed" result)))
    (edn/read-string (:out result))))

(deftest fresh-consumers-reuse-prepared-handlers-across-source-locations
  (let [cache (str (Files/createTempDirectory "aguafria-source-location-bundle-"
                                              (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (run-jvm cache :prepare nil nil)]
    (is (seq (:bundles prepared)))
    (doseq [[filename padding] [["aguafria/zig/source_location_fixture.clj" ""]
                                [(.getAbsolutePath (io/file (io/resource "aguafria/zig/source_location_fixture.clj"))) ""]
                                [(str cache "/relocated/renamed.clj") "\n\n\n      "]]]
      (let [events (:events (run-jvm cache :consume filename padding))
            fallbacks (filter #(#{:disk-cache-hit :compiled :compile-failed} (:event %)) events)]
        (is (some #(= :bundle-cache-hit (:event %)) events))
        (is (empty? fallbacks) (pr-str fallbacks))))))
