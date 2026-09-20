(ns aguafria.zig.builtin-test
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as ak]
            [aguafria.zig :as az]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest builtin-can-be-the-first-required-aguafria-namespace
  (let [result (shell/sh "clojure" "-J--enable-native-access=ALL-UNNAMED" "-M" "-e"
                         (str "(require '[aguafria.builtin :as builtin]) "
                              "(prn @builtin/is_test) (shutdown-agents)"))]
    (is (zero? (:exit result)) (str (:out result) (:err result)))
    (is (= "false" (str/trim (:out result))))))

(deftest compiler-builtin-values-are-real-jvm-values
  (is (false? @builtin/is_test))
  (is (true? (ak/== builtin/is_test false)))
  (is (true? (ak/== builtin/mode :.Debug)))
  (is (= (az/field builtin/cpu :arch)
         (az/field (az/field builtin/target :cpu) :arch)))
  (is (= "@import(\"builtin\").is_test"
         (:zig/name (meta #'builtin/is_test))))
  (is (str/includes? (with-out-str (az/zig-source! #'builtin/is_test))
                     "@import(\"builtin\").is_test")))

(deftest builtin-members-follow-the-consuming-compilation
  (let [namespace (create-ns (gensym "aguafria.builtin-test-fixture-"))]
    (try
      (binding [*ns* namespace]
        (refer 'clojure.core)
        (require '[aguafria.builtin :as builtin]
                 '[aguafria.zig :as az]
                 '[aguafria.std.testing :as testing])
        (eval '(az/defn isATest :bool [] builtin/is_test))
        (eval '(az/deftest detects-test-mode
                 (try (testing/expect (isATest))))))
      (is (false? ((ns-resolve namespace 'isATest))))
      (is (= :passed (:status ((ns-resolve namespace 'detects-test-mode)))))
      (finally (remove-ns (ns-name namespace))))))
