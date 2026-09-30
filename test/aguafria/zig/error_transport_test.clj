(ns aguafria.zig.error-transport-test
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def errors [:error-set [:First :Second]])
(def result-type [:error-union errors :i32])

(defn- printed [value]
  (let [output (java.io.StringWriter.)]
    (binding [*out* output *err* output]
      (debug/print "{any}\n" [value]))
    (str output)))

(deftest native-error-names-survive-calls-and-assignment
  (with-open [first-error (k/as (az/error-value :First) result-type)
              second-error (k/as (az/error-value :Second) result-type)
              target (k/var first-error)]
    (is (str/includes? (printed target) "error.First"))
    (k/= target second-error)
    (is (= :Second (get-in (az/value target) [:error :name])))
    (is (str/includes? (printed target) "error.Second"))
    (k/= target 1234)
    (is (= {:ok 1234} (az/value target)))
    (is (str/includes? (printed target) "1234"))
    (k/= target first-error)
    (is (= :First (get-in (az/value target) [:error :name])))))

(deftest nested-errors-survive-array-optional-and-success-payload-transport
  (let [type [:array 3 [:optional result-type]]]
    (with-open [values (k/as [{:error {:name :Second}} {:ok 42} nil] type)
                target (k/var values)]
      (is (str/includes? (printed target) "error.Second"))
      (is (= :Second (get-in (az/value target) [0 :error :name])))
      (is (= {:ok 42} (get (az/value target) 1)))
      (is (nil? (get (az/value target) 2)))
      (with-open [replacement (k/as [{:ok 5} {:error {:name :First}} nil] type)]
        (k/= target replacement)
        (is (= {:ok 5} (get (az/value target) 0)))
        (is (= :First (get-in (az/value target) [1 :error :name])))
        (is (str/includes? (printed target) "error.First")))))
  (with-open [outer (k/as {:ok {:error {:name :First}}}
                          [:error-union errors result-type])]
    (is (str/includes? (printed outer) "error.First"))))

(deftest raw-error-codes-and-unknown-names-are-not-portable-inputs
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"image-local"
                        (k/as {:error {:code 1}} result-type)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot represent"
                        (k/as {:error {:name :NotInThisSet}} result-type))))

(deftest unsupported-error-storage-fails-without-silent-copying
  ;; Zig cannot reflect the members of anyerror, and copying an error-bearing
  ;; slice to new pointees would silently change aliases and mutation semantics.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot represent"
                        (k/as {:error {:name :First}} [:error-union :anyerror :i32])))
  (with-open [slice (k/as [{:error {:name :First}}] [:slice result-type])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"alias-preserving"
                          (printed slice)))))
