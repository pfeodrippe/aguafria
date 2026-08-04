(ns ghostty-agua.core-test
  (:require [aguafria.zig :as az]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ghostty-agua.core :as example]
            [ghostty-agua.generate :as generate]
            [ghostty-agua.live :as live]
            [ghostty-agua.native :as native]))

(deftest generated-project-summary-test
  (let [{:keys [input-root output-root report-output]} (generate/project-paths)
        report (edn/read-string (slurp report-output))]
    (is (.isDirectory (io/file input-root)))
    (is (generate/generated? output-root))
    (is (= 764 (:file-count report)))
    (is (= 13832 (:declaration-count report)))
    (is (= (:declaration-count report)
           (:structural-declaration-count report)))
    (is (zero? (:raw-declaration-count report)))
    (is (zero? (:fallback-count report)))
    (is (zero? (:unresolved-syntax-count report)))))

(deftest same-jvm-native-terminal-test
  (let [session (native/open!)]
    (try
      (native/write! session
                     "Bonjour\r\n\u001b[34mbleu\u001b[0m\u001b]2;Ghostty Agua\u0007")
      (native/resize! session 100 30 9 18)
      (let [state (native/state session)]
        (is (= 100 (:cols state)))
        (is (= 30 (:rows state)))
        (is (= 900 (:width-px state)))
        (is (= 540 (:height-px state)))
        (is (= 1 (:cursor-y state)))
        (is (= "Ghostty Agua" (:title state)))
        (is (false? (:vt-processing-error? state))))
      (is (str/starts-with? (native/type-layout-json session)
                            "{\"GhosttyStyle\""))
      (finally
        (native/close! session)))))

(deftest compatible-native-edit-retains-terminal-test
  (let [original (:aguafria/declaration (meta #'live/title-version))]
    (try
      (az/await! 'ghostty-agua.live)
      (example/start! {:replace? true})
      (let [before (example/publish-hot-title!)
            changed (runtime/declaration-info (assoc original :body [2]))]
        (runtime/register-declaration! changed)
        (az/await! 'ghostty-agua.live)
        (let [after (example/publish-hot-title!)]
          (testing "the function body changes while real Ghostty state remains"
            (is (= 1 (:native-version before)))
            (is (= 2 (:native-version after)))
            (is (= (:terminal-address before) (:terminal-address after)))
            (is (= "Aguafria Ghostty hot generation 2" (:title after))))))
      (finally
        (runtime/register-declaration! original)
        (az/await! 'ghostty-agua.live)
        (example/stop!)))))
