(ns aguafria.zig.discovery-inspection-test
  (:require [aguafria.zig.discovery :as discovery]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(def ^:private fixture-file
  (.getCanonicalPath
   (if-let [resource (io/resource "aguafria/zig/discovery_inspection_fixture.clj")]
     (io/file resource)
     (io/file (.getParentFile (io/file *file*)) "discovery_inspection_fixture.clj"))))

(deftest inspection-preserves-context-and-reaches-concrete-members
  (binding [runtime/*source-only-registration?* true]
    (load-file fixture-file))
  (with-redefs [runtime/invoke! (fn [& _] (throw (ex-info "Unexpected native execution" {})))
                runtime/invoke-with-result! (fn [& _] (throw (ex-info "Unexpected native execution" {})))]
    (let [report (discovery/analyze! 'aguafria.zig.discovery-inspection-fixture)
          operations (:operations report)
          operation (fn [function]
                      (first (filter #(= function (:function %)) operations)))]
      (is (zero? (get-in report [:baseline :exit])) (get-in report [:baseline :diagnostics]))
      (is (= :observed (:status (operation 'aguafria.zig.discovery-inspection-fixture/Holder))))
      (is (= :observed (:status (operation 'aguafria.zig.discovery-inspection-fixture/stop-now))))
      (is (= :observed (:status (operation 'aguafria.keyword/panic))))
      (is (= :observed (:status (operation 'aguafria.keyword/=))))
      (is (empty? (:probe-failures report))))))
