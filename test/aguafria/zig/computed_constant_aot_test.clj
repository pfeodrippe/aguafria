(ns aguafria.zig.computed-constant-aot-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(defn- child [form]
  (shell/sh (str (System/getProperty "java.home") "/bin/java")
            "-Xmx768m" "-XX:ActiveProcessorCount=2" "--enable-native-access=ALL-UNNAMED"
            "-cp" (System/getProperty "java.class.path") "clojure.main" "-e" (pr-str form)))

(deftest computed-constant-inspection-is-prepared-without-realizing-storage
  (let [cache (str (Files/createTempDirectory "aguafria-computed-constant-aot-"
                                              (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (child
                  `(do
                     (require '~'aguafria.zig '~'aguafria.zig.runtime)
                     (~'aguafria.zig/configure! {:cache-dir ~cache})
                     (let [original# ~'aguafria.zig.runtime/materialize-constant!]
                       (with-redefs [~'aguafria.zig.runtime/materialize-constant!
                                     (fn [declaration#]
                                       (when (= "aguafria.zig.computed-constant-aot-fixture" (:module declaration#))
                                         (throw (ex-info "Preparation realized application storage" {})))
                                       (original# declaration#))]
                         (~'aguafria.zig/precompile!
                          {:analyze '[aguafria.zig.computed-constant-aot-fixture]})))
                     (assert (empty? (get-in (deref (deref (ns-resolve '~'aguafria.zig.runtime '~'registry)))
                                             ["aguafria.zig.computed-constant-aot-fixture" :native-generations])))
                     (shutdown-agents)))]
    (is (zero? (:exit prepared)) (str (:out prepared) (:err prepared)))
    (when (zero? (:exit prepared))
      (let [consumed
            (child
             `(do
                (require '~'aguafria.zig '~'aguafria.zig.value '~'aguafria.zig.compiler-work
                         '~'aguafria.zig.explain)
                (~'aguafria.zig/configure! {:cache-dir ~cache})
                (require '~'aguafria.zig.computed-constant-aot-fixture)
                (let [events# (atom []) attempts# (atom 0)
                      original# ~'aguafria.zig.compiler-work/run-command!]
                  (with-redefs [~'aguafria.zig.compiler-work/run-command!
                                (fn [command# invoke#]
                                  (when (= :compiler ((ns-resolve '~'aguafria.zig.compiler-work '~'command-kind) command#))
                                    (swap! attempts# inc)
                                    (throw (ex-info "Evaluation compiler forbidden" {:command command#})))
                                  (original# command# invoke#))
                                ~'aguafria.zig.explain/event! #(swap! events# conj %)]
                    ;; A pointer preview is not the pointed-to string. Force
                    ;; its normal printer and verify the compiler's exact type.
                    (assert (seq (pr-str ~'aguafria.zig.computed-constant-aot-fixture/message)))
                    (assert (= [:*const [:array 15 {:sentinel 0} :u8]]
                               (~'aguafria.zig.value/storage-type
                                ~'aguafria.zig.computed-constant-aot-fixture/message))))
                  (assert (zero? @attempts#))
                  (assert (not-any? #(#{:compiled :compile-failed :disk-cache-hit} (:event %)) @events#)))
                (shutdown-agents)))]
        (is (zero? (:exit consumed)) (str (:out consumed) (:err consumed)))))))
