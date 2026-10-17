(ns aguafria.zig.enum-literal-prepared-test
  (:require [aguafria.zig.check :as check]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest enum-literals-retain-native-source-across-the-jvm-boundary
  (let [root (str (Files/createTempDirectory "prepared-enum-literal-"
                                           (make-array java.nio.file.attribute.FileAttribute 0)))
        report (check/check!
                {:report-dir (str (io/file root "check"))
                 :compilation-modes [:prepared] :strict-load? true
                 :heap-mib 768 :timeout-seconds 240
                 :precompile-options {:analyze ['aguafria.zig.enum-literal-prepared-fixture]
                                      :parallelism 16}
                 :scenarios [{:id :enum-literal-conversions
                              :call 'aguafria.zig.enum-literal-prepared-driver/conversions
                              :load-files [(.getAbsolutePath
                                            (io/file "test/aguafria/zig/enum_literal_prepared_fixture.clj"))]
                              :args []
                              :expect {:value [[:red :red :red]
                                               [(keyword "really red") (keyword "really red") (keyword "really red")]
                                               [:i32 :i32 :i32]]}}]})
        result (first (:results report))]
    (is (:accepted? report) (str (:directory report)))
    (is (empty? (:final-compiler-attempts result)))
    (is (not-any? #(#{:compiled :compile-failed :disk-cache-hit} (:event %))
                  (:final-all-native-events result)))))
