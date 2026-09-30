(ns aguafria.zig.error-union-precompile-test
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]
            [aguafria.zig.bundle :as bundle]
            [aguafria.zig.precompile :as precompile]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(defn phase! [cache prepare?]
  (az/configure! {:cache-dir cache})
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.discovery-error-union-fixture))
  (let [commands (atom [])
        original shell/sh
        output (java.io.StringWriter.)
        types (atom [])]
    (with-redefs [shell/sh (fn [& arguments]
                             (when (= "build-lib" (second arguments))
                               (swap! commands conj (vec arguments)))
                             (apply original arguments))]
      (if prepare?
        (let [fail! (fn [& _] (throw (ex-info "Native invocation during preparation" {})))
              finish! bundle/finish!]
          ;; Deliberately create independent preparations to exercise the ABI
          ;; boundary. Production preparation always emits one library.
          (with-redefs-fn {#'runtime/invoke! fail! #'runtime/invoke-with-result! fail!
                           #'bundle/finish!
                           (fn [cache collected callbacks]
                             {:packs
                              (vec (mapcat (fn [[id artifact]]
                                              (:packs (finish! cache
                                                               (atom {:artifacts {id artifact}})
                                                               callbacks)))
                                            (:artifacts @collected)))})}
            (fn []
              (let [report (precompile/precompile!
                            {:analyze ['aguafria.zig.discovery-error-union-fixture]
                             :report-file (str cache "/report.edn")})]
                (assert (zero? (get-in report [:coverage :operations :not-fully-prepared]))
                        (pr-str (:coverage report)))
                (assert (> (count (get-in report [:bundles :packs])) 1)
                        "Regression must cross separately compiled bundle images")))))
        (let [errors @(resolve 'aguafria.zig.discovery-error-union-fixture/Errors)]
          ;; The same JVM let body, not a call to the already-compiled main.
          ;; Both compiler-known members must reuse the prepared conversions.
          (doseq [member [:First :Second]]
            (binding [*out* output *err* output]
              (with-open [number-or-error (-> (az/field errors member)
                                              (k/as [:error-union errors :i32])
                                              k/var)]
                (swap! types conj (:type (value/type-info (k/TypeOf number-or-error))))
                (debug/print "type: {}, value: {!}\n"
                             [(k/TypeOf number-or-error) number-or-error])
                (k/= number-or-error 1234)
                (debug/print "after: {}, value: {!}\n"
                             [(k/TypeOf number-or-error) number-or-error])))))))
    {:builds (count @commands)
     :commands @commands
     :types @types
     :output (str output)}))

(defn- child! [cache prepare?]
  (let [code `(do
                (require 'aguafria.zig.error-union-precompile-test)
                (prn (phase! ~cache ~prepare?))
                (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Error-union preparation child failed" result)))
    (edn/read-string (:out result))))

(deftest error-union-preparation-survives-a-jvm-restart
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "error-union-" (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (child! cache true)
        restarted (child! cache false)]
    (is (pos? (:builds prepared)))
    (is (zero? (:builds restarted))
        (pr-str (mapv #(last %) (:commands restarted))))
    (is (= (repeat 2 [:error-union [:error-set [:First :Second]] :i32])
           (:types restarted)))
    (doseq [expected ["value: error.First" "value: error.Second" "value: 1234"]]
      (is (str/includes? (:output restarted) expected)))))
