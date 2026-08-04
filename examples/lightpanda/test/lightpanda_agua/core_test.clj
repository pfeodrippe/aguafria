(ns lightpanda-agua.core-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [lightpanda-agua.core :as example]
            [lightpanda-agua.generate :as generate]))

(deftest complete-generated-project-test
  (let [{:keys [input-root output-root report-output]}
        (generate/project-paths)
        report (edn/read-string (slurp report-output))]
    (is (.isDirectory (io/file input-root)))
    (is (generate/generated? output-root))
    (is (= 525 (:file-count report)))
    (is (= 16267 (:declaration-count report)))
    (is (= (:declaration-count report)
           (:structural-declaration-count report)))
    (is (zero? (:raw-declaration-count report)))
    (is (zero? (:fallback-count report)))
    (is (zero? (:unresolved-syntax-count report)))
    (is (= 512 (:asset-file-count report)))
    (is (= "1.0.0-dev.8500+e1435339" (:lightpanda/version report)))))

(deftest generated-declarations-are-ordinary-clojure-vars-test
  (testing "the browser entry point and direct Zig functions are Vars"
    (is (var? (example/main-var)))
    (is (var? (ns-resolve 'lightpanda.src.cdp.id 'parseFrameId)))
    (is (var? (ns-resolve 'lightpanda.src.cdp.id 'Incrementing)))))

