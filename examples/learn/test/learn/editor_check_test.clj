(ns learn.editor-check-test
  (:require [aguafria.zig]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [learn.editor-check :as editor])
  (:import [java.nio.file Files]))

(defn- fixture! [body]
  (let [directory (Files/createTempDirectory "learn-editor-check-" (make-array java.nio.file.attribute.FileAttribute 0))
        namespace (gensym "learn.editor.check.fixture")
        path (str (.resolve directory "lesson.clj"))
        checkpoint (str (.resolve directory "checkpoint.edn"))]
    (spit path (str "(ns " namespace ")\n(def seen (atom []))\n(defn main []\n" body ")\n"))
    (load-file path)
    {:namespace namespace :path path :checkpoint checkpoint}))

(deftest exact-editor-sequence-stops-on-first-thrown-failure
  (let [{:keys [namespace path checkpoint]}
        (fixture! "(swap! seen conj :first)\n(throw (ex-info \"expected\" {}))\n(swap! seen conj :must-not-run)")]
    (try
      (binding [*out* (java.io.StringWriter.)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expected"
                              (editor/evaluate! namespace path checkpoint))))
      (is (= [:first] @(var-get (ns-resolve namespace 'seen))))
      (let [result (edn/read-string (slurp checkpoint))]
        (is (= :failed (:status result)))
        (is (= 1 (count (:completed result))))
        (is (= 2 (count (:remaining result)))))
      (finally (remove-ns namespace)))))

(deftest error-set-constants-are-values-not-evaluation-failures
  (let [{:keys [namespace path checkpoint]}
        (fixture! "(aguafria.zig.value/->ZigError :FileNotFound [:error-set [:FileNotFound]])\n(swap! seen conj :continued)")]
    (try
      (let [result (editor/evaluate! namespace path checkpoint)
            receipt (edn/read-string (slurp checkpoint))]
        (is (= :passed (:editor-status result)))
        (is (= 2 (:evaluated result)))
        (is (= 1 (:returned-error-values result)))
        (is (true? (:returned-native-error? (first (:completed receipt)))))
        (is (= [:continued] @(var-get (ns-resolve namespace 'seen)))))
      (finally (remove-ns namespace)))))
