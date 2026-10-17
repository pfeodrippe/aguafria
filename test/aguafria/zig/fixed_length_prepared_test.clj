(ns aguafria.zig.fixed-length-prepared-test
  (:require [aguafria.zig :as a :refer [field]]
            [aguafria.zig.check :as check]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.emitter :as emitter]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest length-observation-requires-native-var-identity-and-argument-position
  (binding [emitter/*keyword-context* (the-ns 'aguafria.zig.fixed-length-prepared-test)
            emitter/*lexical-bindings* #{'array}]
    (let [observe (fn [form kind] (#'discovery/operand-schema pr-str "result" nil form kind))
          known? #(str/includes? % "fixedArrayLengthArgument")]
      (is (known? (observe '(a/field array :len) "argumentSchema")))
      (is (known? (observe '(field array "len") "argumentSchema")))
      (is (known? (observe '(:len array) "argumentSchema")))
      (is (known? (observe (with-meta '(a/field array :len)
                            {:aguafria/jvm-field-source '(:len array)})
                          "argumentSchema")))
      (is (not (known? (observe '(:len array) "schema"))))
      (is (not (known? (observe '(:other array) "argumentSchema"))))
      (is (not (known? (observe '(a/field array :len) "schema"))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unresolved Zig reference"
                            (observe '(clojure.core/get array :len) "argumentSchema")))
      (is (not (known? (observe '(a/field array :other) "argumentSchema"))))
      (binding [emitter/*lexical-bindings* #{'array 'field}]
        (is (not (known? (observe '(field array :len) "argumentSchema"))))))))

(deftest compiler-known-array-lengths-survive-strict-restart
  (let [directory (str (Files/createTempDirectory "aguafria-fixed-length-"
                                                  (make-array java.nio.file.attribute.FileAttribute 0)))
        report (check/check!
                {:report-dir (str directory "/check")
                 :compilation-modes [:prepared] :strict-load? true
                 :parallelism 1 :heap-mib 768 :timeout-seconds 180
                 :precompile-options {:analyze ['aguafria.zig.fixed-length-prepared-fixture]
                                      :parallelism 16}
                 :scenarios [{:id :fixed-length
                              :call 'aguafria.zig.fixed-length-prepared-driver/evaluate!
                              :args []
                              :expect {:value {:length 4 :length-type true :fixed true
                                               :runtime true :slice-end-runtime true
                                               :copied-end-runtime true :element 3 :pointer-length 4}}}]})
        result (first (:results report))]
    (is (:accepted? report) (:directory report))
    (is (empty? (:final-compiler-attempts result)))
    (is (not-any? #(#{:compiled :compile-failed :disk-cache-hit} (:event %))
                  (:final-all-native-events result)))
    (is (some #(= :bundle-cache-hit (:event %)) (:final-all-native-events result)))))
