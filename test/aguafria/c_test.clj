(ns aguafria.c-test
  (:require [aguafria.c :as ac]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest translate-header-and-inspect-bindings-test
  (let [directory (.toFile
                   (java.nio.file.Files/createTempDirectory
                    "aguafria-c-binding-test"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        output (io/file directory "generated" "fixture.clj")
        options {:namespace 'aguafria.generated.c-fixture
                 :cache-dir (str (io/file directory "cache"))
                 :overwrite? true}
        first-report (ac/translate-header! "test/fixtures/c_binding_fixture.h"
                                           output options)
        second-report (ac/translate-header! "test/fixtures/c_binding_fixture.h"
                                            output options)]
    (testing "Zig translate-c feeds ordinary structural Aguafria generation"
      (is (.isFile output))
      (is (false? (:cache-hit? first-report)))
      (is (true? (:cache-hit? second-report)))
      (is (zero? (:fallback-count first-report)))
      (is (not (str/includes? (slurp output) "az/defraw"))))

    (testing "generated C declarations are ordinary documented Vars"
      (ac/load-bindings! output)
      (let [{:keys [bindings]} (ac/namespace-info 'aguafria.generated.c-fixture)
            add (some #(when (= 'agua_add (:name %)) %) bindings)
            point (some #(when (= 'agua_point_t (:name %)) %) bindings)
            counter (some #(when (= 'agua_external_counter (:name %)) %)
                          bindings)]
        (is (= :fn-proto (:kind add)))
        (is (= :c_int (:return add)))
        (is (= :const (:kind point)))
        (is (= :extern-var (:kind counter)))
        (is (str/includes? (:doc add) "Add two signed integers"))))))
