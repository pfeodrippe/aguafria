(ns learn.editor-check-linkage-test
  (:require [aguafria.zig :as a]
            [aguafria.zig.build :as build]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [learn.editor-check-linkage :as linkage])
  (:import [java.nio.file Files]))

(deftest companion-linkage-is-compile-only-frozen-and-verified
  (let [root (str (Files/createTempDirectory "learn-editor-linkage-"
                                           (make-array java.nio.file.attribute.FileAttribute 0)))
        object (str (io/file root "float.o"))
        receipt-file (str (io/file root "linkage.edn"))
        calls (atom [])
        receipt (with-redefs [build/load-source-only!
                              (fn [n]
                                (is runtime/*compile-only?*)
                                (swap! calls conj [:load n]))
                              a/build!
                              (fn [n options]
                                (is runtime/*compile-only?*)
                                (swap! calls conj [:build n options])
                                (spit object "frozen compiled bytes")
                                {:module (str n) :output-path object :kind :object})]
                  (linkage/prepare!
                   {:selected [{:namespace 'learn.example.float-mode-obj}
                               {:namespace 'learn.example.float-mode-exe}]}
                   receipt-file))]
    (is (= [[:load 'learn.example.float-mode-obj]
            [:build 'learn.example.float-mode-obj {:kind :object :optimize "fast" :zig-args ["-fPIC"]}]]
           @calls))
    (is (= [object] (get-in receipt [:configuration :zig-args-by-module "learn.example.float-mode-exe"])))
    (is (= ['learn.example.float-mode-exe 'learn.example.float-mode-obj] (:linkage-contexts receipt)))
    (with-redefs [a/build! (fn [& _] (throw (Exception. "Consumer must not build")))
                  build/load-source-only! (fn [& _] (throw (Exception. "Consumer must not preload")))]
      (is (= receipt (linkage/read! receipt-file))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exists"
                          (linkage/prepare! {:selected []} receipt-file)))
    (spit object "changed")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing or changed" (linkage/read! receipt-file)))
    (spit receipt-file (pr-str (assoc-in receipt [:artifacts 0 :output-path]
                                       (str (io/file root "missing.o")))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing or changed" (linkage/read! receipt-file)))
    (spit receipt-file (pr-str (assoc receipt :schema :unknown)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown Learn linkage receipt" (linkage/read! receipt-file)))))

(deftest selected-abi-owners-are-allowed-without-inventing-link-inputs
  (let [root (str (Files/createTempDirectory "learn-editor-export-context-"
                                           (make-array java.nio.file.attribute.FileAttribute 0)))
        file (str (io/file root "linkage.edn"))
        receipt (with-redefs [build/load-source-only! (fn [& _] (throw (Exception. "No companion load")))
                              a/build! (fn [& _] (throw (Exception. "No companion build")))]
                  (linkage/prepare!
                   {:selected [{:namespace 'learn.example.enum-export}
                               {:namespace 'learn.example.hello}
                               {:namespace 'learn.example.enum-export}]} file))]
    (is (= ['learn.example.enum-export 'learn.example.hello] (:linkage-contexts receipt)))
    (is (empty? (:artifacts receipt)))
    (is (empty? (:configuration receipt)))
    (is (= receipt (linkage/read! file)))))
