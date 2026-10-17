(ns aguafria.zig.named-error-alias-native-test
  (:require [aguafria.zig.check :as check]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest named-error-aliases-retain-name-based-argument-and-result-transport
  (let [directory (str (Files/createTempDirectory "aguafria-named-error-alias-"
                                                  (make-array java.nio.file.attribute.FileAttribute 0)))
        report (check/check!
                {:report-dir (str directory "/check")
                 :compilation-modes [:prepared] :strict-load? true
                 :parallelism 1 :heap-mib 768 :timeout-seconds 180
                 :precompile-options {:analyze ['aguafria.zig.named-error-alias-fixture]
                                      :parallelism 16}
                 :scenarios [{:id :named-error-alias
                              :call 'aguafria.zig.named-error-alias-driver/evaluate!
                              :args []
                              :expect {:value ["OutOfMemory"
                                               [:error-set [:AccessDenied :FileNotFound :OutOfMemory]]
                                               "OutOfMemory" :error-name-not-in-closed-set true]}}]})
        result (first (:results report))]
    (is (:accepted? report) (:directory report))
    (is (empty? (:final-compiler-attempts result)))
    (is (not-any? #(#{:compiled :compile-failed :disk-cache-hit} (:event %))
                  (:final-all-native-events result)))
    (is (some #(= :bundle-cache-hit (:event %)) (:final-all-native-events result)))))
