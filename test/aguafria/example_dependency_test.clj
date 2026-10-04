(ns aguafria.example-dependency-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest ordinary-examples-and-precompilation-use-the-same-core
  (let [root (-> (io/resource "aguafria/example_dependency_test.clj")
                 .toURI io/file .getParentFile .getParentFile .getParentFile)]
    (doseq [name ["la-professeure" "racing-game" "http-server"]]
      (testing name
        (let [directory (io/file root "examples" name)
              config (edn/read-string (slurp (io/file directory "deps.edn")))
              dependency (get-in config [:deps 'aguafria/aguafria])
              alias-deps (mapcat #(vals (select-keys % [:extra-deps :override-deps :replace-deps]))
                                 (vals (:aliases config)))
              all-libraries (mapcat keys (cons (:deps config) alias-deps))]
          (is (= {:local/root "../.."} dependency))
          (is (= (.getCanonicalFile root)
                 (.getCanonicalFile (io/file directory (:local/root dependency)))))
          (is (not-any? #(str/starts-with? (str %) "io.github.pfeodrippe/aguafria-")
                        all-libraries))
          (is (not-any? #(contains? % 'aguafria/aguafria) alias-deps))
          (is (not-any? #{'aguafria/aguafria}
                        (get-in config [:deps 'aguafria/examples-native :exclusions]))))))))
