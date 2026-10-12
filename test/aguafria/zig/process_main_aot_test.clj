(ns aguafria.zig.process-main-aot-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(defn- child [form]
  (shell/sh (str (System/getProperty "java.home") "/bin/java")
            "-Xmx768m" "-XX:ActiveProcessorCount=2" "--enable-native-access=ALL-UNNAMED"
            "-cp" (System/getProperty "java.class.path") "clojure.main" "-e" (pr-str form)))

(deftest process-entry-preparation-does-not-start-hosts-and-repeated-consumption-is-isolated
  (let [cache (str (Files/createTempDirectory "aguafria-process-aot-"
                                              (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (child
                  `(do
                     (require '~'aguafria.zig '~'aguafria.zig.runtime)
                     (~'aguafria.zig/configure! {:cache-dir ~cache})
                     (with-redefs [~'aguafria.zig.runtime/start-process-main!
                                   (fn [& _#] (throw (ex-info "Preparation started a process host" {})))]
                       (let [r# (~'aguafria.zig/precompile! {:namespaces '[aguafria.zig.process-main-aot-fixture]})]
                         (assert (some #(and (= :prepared (:status %)) (= :native-process-host (:execution-context %))) (:functions r#)))
                         (assert (empty? (get-in (deref (deref (ns-resolve '~'aguafria.zig.runtime '~'registry)))
                                                 ["aguafria.zig.process-main-aot-fixture" :native-generations])))))
                     (shutdown-agents)))]
    (is (zero? (:exit prepared)) (str (:out prepared) (:err prepared)))
    (when (zero? (:exit prepared))
      (let [consumed
            (child
             `(do
                (require '~'aguafria.zig '~'aguafria.zig.runtime '~'aguafria.zig.compiler-work '~'aguafria.zig.explain)
                (~'aguafria.zig/configure! {:cache-dir ~cache})
                (require '~'aguafria.zig.process-main-aot-fixture)
                (let [events# (atom []) attempts# (atom 0)]
                  (with-redefs [~'aguafria.zig.compiler-work/run-command!
                                (fn [& _#] (swap! attempts# inc) (throw (ex-info "Evaluation compiler forbidden" {})))
                                ~'aguafria.zig.explain/event! #(swap! events# conj %)]
                    (dotimes [_# 2]
                      (let [host# (~'aguafria.zig.runtime/start-process-main!
                                   '~'aguafria.zig.process-main-aot-fixture/main [] {:share-state? false})
                            result# (~'aguafria.zig.runtime/await-host! host#)]
                        (assert (= :finished (:status result#)))
                        (assert (zero? (:exit-code result#))))))
                  (assert (zero? @attempts#))
                  (assert (not-any? #(#{:compiled :compile-failed :disk-cache-hit} (:event %)) @events#))
                  (let [paths# (map :path (filter #(= :bundle-host-loaded (:event %)) @events#))]
                    (assert (= 2 (count paths#) (count (set paths#))))
                    (assert (not-any? #(.exists (java.io.File. %)) paths#))))
                (shutdown-agents)))]
        (is (zero? (:exit consumed)) (str (:out consumed) (:err consumed)))))))
