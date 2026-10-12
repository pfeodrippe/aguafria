(ns aguafria.zig.native-test-aot-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(defn- child [form]
  (shell/sh (str (System/getProperty "java.home") "/bin/java")
            "-Xmx768m" "-XX:ActiveProcessorCount=2" "--enable-native-access=ALL-UNNAMED"
            "-cp" (System/getProperty "java.class.path") "clojure.main" "-e" (pr-str form)))

(deftest prepared-test-objects-share-a-pack-without-sharing-selected-test-state
  (let [root (str (Files/createTempDirectory "aguafria-native-test-aot-"
                                             (make-array java.nio.file.attribute.FileAttribute 0)))
        cache (str root "/cache")
        tests '[aguafria.zig.native-test-aot-fixture/first-isolated-test
                aguafria.zig.native-test-aot-fixture/second-isolated-test
                aguafria.zig.native-test-aot-fixture/skipped-test
                aguafria.zig.native-test-aot-fixture/failing-test
                aguafria.zig.native-test-aot-fixture/panic-test]
        prepared (child
                  `(do
                     (require '~'aguafria.zig '~'aguafria.zig.runtime)
                     (~'aguafria.zig/configure! {:cache-dir ~cache})
                     (let [report# (with-redefs [~'aguafria.zig.runtime/run-test!
                                                 (fn [& _#] (throw (ex-info "Preparation executed a test" {})))]
                                     (~'aguafria.zig/precompile!
                                      {:tests '~tests :report-file ~(str root "/report.edn")}))]
                       (assert (every? #(= :prepared (:status %)) (:tests report#)))
                       (assert (= 1 (count (get-in report# [:bundles :packs]))))
                       (assert (empty? (get-in report# [:bundles :standalone])))
                       (assert (empty? (get-in (deref (deref (ns-resolve '~'aguafria.zig.runtime '~'registry)))
                                               ["aguafria.zig.native-test-aot-fixture" :native-generations]))))
                     (shutdown-agents)))]
    (is (zero? (:exit prepared)) (str (:out prepared) (:err prepared)))
    (when (zero? (:exit prepared))
      (let [consumed
            (child
             `(do
                (require '~'aguafria.zig '~'aguafria.zig.compiler-work '~'aguafria.zig.explain)
                (~'aguafria.zig/configure! {:cache-dir ~cache})
                (require '~'aguafria.zig.native-test-aot-fixture)
                (let [events# (atom []) attempts# (atom 0)]
                  (with-redefs [~'aguafria.zig.compiler-work/run-command!
                                (fn [& _#] (swap! attempts# inc)
                                  (throw (ex-info "Evaluation compiler work forbidden" {})))
                                ~'aguafria.zig.explain/event! #(swap! events# conj %)]
                    (assert (= :passed (:status ((resolve '~(nth tests 0))))))
                    (assert (= :passed (:status ((resolve '~(nth tests 1))))))
                    ;; Repetition must start with fresh test-local globals too.
                    (assert (= :passed (:status ((resolve '~(nth tests 0))))))
                    (assert (= :passed (:status ((resolve '~(nth tests 1))))))
                    (assert (= :skipped (:status ((resolve '~(nth tests 2))))))
                    (assert (= :zig-test
                               (try ((resolve '~(nth tests 3))) nil
                                    (catch clojure.lang.ExceptionInfo e# (:aguafria/phase (ex-data e#))))))
                    (assert (= :native-panic
                               (try ((resolve '~(nth tests 4))) nil
                                    (catch clojure.lang.ExceptionInfo e#
                                      (let [details# (ex-data e#)]
                                        (assert (= '~(nth tests 4) (:clojure.error/symbol details#)))
                                        (assert (pos? (:clojure.error/line details#)))
                                        (:aguafria/phase details#))))))
                    (assert (= :passed (:status ((resolve '~(nth tests 0)))))))
                  (assert (zero? @attempts#))
                  (assert (= 8 (count (filter #(= :bundle-cache-hit (:event %)) @events#))))
                  (assert (not-any? #(#{:compiled :compile-failed :disk-cache-hit} (:event %)) @events#))
                  (assert (not-any? #(.startsWith (.getName %) "isolated_test_")
                                    (file-seq (java.io.File. ~cache)))))
                (shutdown-agents)))]
        (is (zero? (:exit consumed)) (str (:out consumed) (:err consumed)))
        (is (.contains (:err consumed) "SKIP"))
        (is (.contains (:err consumed) "TestUnexpectedResult"))))))
