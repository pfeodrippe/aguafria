(ns aguafria.zig.extern-load-prepared-test
  (:require [aguafria.zig.check :as check]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest unlinked-final-definition-validation-is-prepared-with-its-load-context
  (let [root (str (Files/createTempDirectory "prepared-extern-load-"
                                           (make-array java.nio.file.attribute.FileAttribute 0)))
        report (check/check!
                {:report-dir (str (io/file root "check"))
                 :compilation-modes [:prepared] :strict-load? true
                 :heap-mib 768 :timeout-seconds 240
                 :precompile-options {:analyze ['aguafria.zig.extern-load-prepared-fixture]
                                      :parallelism 16}
                 :scenarios [{:id :load-only
                              :load-files [(.getAbsolutePath
                                            (io/file "test/aguafria/zig/extern_load_prepared_fixture.clj"))]
                              :call 'clojure.core/identity :args [:loaded]
                              :expect {:value :loaded}}]})
        result (first (:results report))]
    (is (:accepted? report) (str (:directory report)))
    (is (empty? (:final-compiler-attempts result)))
    (is (not-any? #(#{:compiled :compile-failed :disk-cache-hit} (:event %))
                  (:final-all-native-events result)))))
