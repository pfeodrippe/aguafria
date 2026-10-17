(ns aguafria.zig.test-definition-prepared-test
  (:require [aguafria.zig.check :as check]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest ordinary-loading-consumes-frozen-test-prefixes-without-running-tests
  (let [root (str (Files/createTempDirectory "prepared-test-prefix-" (make-array java.nio.file.attribute.FileAttribute 0)))
        fixture 'aguafria.zig.test-definition-prepared-fixture
        report (check/check!
                {:report-dir (str (io/file root "check"))
                 :compilation-modes [:prepared] :strict-load? true
                 :heap-mib 768 :timeout-seconds 240
                 :precompile-options {:analyze [fixture] :parallelism 16}
                 :scenarios [{:id :definition-prefixes :call 'aguafria.zig.test-definition-prepared-driver/loaded
                              :load-files [(.getAbsolutePath (io/file "test/aguafria/zig/test_definition_prepared_fixture.clj"))]
                              :args [] :expect {:value :loaded}}]})
        producer (edn/read-string (slurp (io/file root "check/prepared-cache/preparation.edn")))
        checks (:test-definition-checks producer)
        sources (mapv #(slurp (get-in % [:artifact :source-path])) checks)
        result (first (:results report))]
    (is (:accepted? report) (str (:directory report)))
    (is (= 2 (count checks)))
    (is (= {:prepared 2} (get-in producer [:coverage :test-definition-checks])))
    (is (not (str/includes? (first sources) "fn later")))
    (is (str/includes? (second sources) "fn later"))
    (is (empty? (:final-compiler-attempts result)))
    (is (not-any? #(#{:compiled :compile-failed :disk-cache-hit} (:event %)) (:final-all-native-events result)))))
