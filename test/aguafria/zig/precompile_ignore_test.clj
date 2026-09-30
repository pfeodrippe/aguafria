(ns aguafria.zig.precompile-ignore-test
  (:require [aguafria.zig.precompile :as precompile]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(defn- temporary-directory []
  (.toFile (Files/createTempDirectory "aguafria-precompile-ignore-"
                                      (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest ignore-prevents-loading-explicit-namespace-and-call-selections
  (let [directory (temporary-directory)
        path (str (io/file directory "report.edn"))
        report (precompile/precompile!
                {:namespaces '[this.namespace.does.not.exist]
                 :analyze '[this.namespace.does.not.exist]
                 :calls '[{:function this.namespace.does.not.exist/f :args []}]
                 :ignore '#{this.namespace.does.not.exist}
                 :bundle? false :report-file path})]
    (is (= [{:namespace 'this.namespace.does.not.exist :status :ignored :reason :explicit-ignore}]
           (:ignored report)))
    (is (empty? (:analysis report)))
    (is (empty? (:functions report)))
    (is (empty? (:calls report)))
    (is (= 1 (get-in report [:coverage :namespaces :ignored])))
    (is (zero? (get-in report [:coverage :namespaces :attempted])))
    (is (zero? (get-in report [:coverage :operations :fully-prepared])))
    (is (= (:ignored report) (:ignored (edn/read-string (slurp path)))))))

(deftest source-directory-selections-can-be-ignored
  (let [directory (temporary-directory)
        source (io/file directory "ignored.clj")]
    (spit source "(ns ignored.precompile.fixture)\n(throw (Exception. \"must not load\"))\n")
    (let [report (precompile/precompile!
                  {:source-dirs [(str directory)] :ignore '[ignored.precompile.fixture]
                   :bundle? false :report-file (str (io/file directory "report.edn"))})]
      (is (= 'ignored.precompile.fixture (get-in report [:ignored 0 :namespace])))
      (is (empty? (:analysis report)))
      (is (nil? (find-ns 'ignored.precompile.fixture))))))

(deftest malformed-ignore-options-are-rejected
  (doseq [ignore ['some.ns ["some.ns"] '[some.ns/function] {:namespace 'some.ns}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (precompile/precompile! {:analyze '[some.ns] :ignore ignore})))))
