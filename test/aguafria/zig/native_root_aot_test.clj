(ns aguafria.zig.native-root-aot-test
  (:require [aguafria.zig.bundle :as bundle]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(defn- child [root form]
  (shell/sh (str (System/getProperty "java.home") "/bin/java")
            "-Xmx768m" "-XX:ActiveProcessorCount=2" "--enable-native-access=ALL-UNNAMED"
            (str "-Djava.io.tmpdir=" root)
            "-cp" (System/getProperty "java.class.path") "clojure.main" "-e" (pr-str form)))

(deftest root-frontend-errors-use-admission-isolation-but-crashes-stay-fatal
  (let [root (str (Files/createTempDirectory "aguafria-root-validation-"
                                             (make-array java.nio.file.attribute.FileAttribute 0)))]
    (with-redefs-fn {#'bundle/materialize-entries!
                     (fn [& _] (throw (ex-info "root frontend" {:exit 1})))}
      #(is (false? (#'bundle/validate-batch! root [] nil true))))
    (with-redefs-fn {#'bundle/materialize-entries!
                     (fn [& _] (throw (ex-info "root compiler crash" {:exit 134})))}
      #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"root compiler crash"
                             (#'bundle/validate-batch! root [] nil true))))))

(deftest library-root-panic-semantics-survive-preparation-without-taking-over-peer-roots
  (let [root (str (Files/createTempDirectory "aguafria-native-root-aot-"
                                             (make-array java.nio.file.attribute.FileAttribute 0)))
        cache (str root "/cache")
        prepared (child root
                        `(do
                           (require '~'aguafria.zig '~'aguafria.zig.runtime)
                           (~'aguafria.zig/configure! {:cache-dir ~cache})
                           (let [report# (~'aguafria.zig/precompile!
                                          {:namespaces '[aguafria.zig.native-root-aot-fixture]
                                           :tests '[aguafria.zig.native-root-peer-fixture/ordinary-panic]})]
                             (assert (= 1 (count (get-in report# [:bundles :packs]))))
                             (assert (empty? (get-in report# [:bundles :standalone])))
                             (assert (empty? (get-in (deref (deref (ns-resolve '~'aguafria.zig.runtime '~'registry)))
                                                     ["aguafria.zig.native-root-aot-fixture" :native-generations]))))
                           (shutdown-agents)))
        evidence (str root "/checkpoint.edn")]
    (is (zero? (:exit prepared)) (str (:out prepared) (:err prepared)))
    (when (zero? (:exit prepared))
      (let [consumed (child root
                            `(do
                               (require '~'aguafria.zig '~'aguafria.zig.compiler-work '~'aguafria.zig.explain)
                               (~'aguafria.zig/configure! {:cache-dir ~cache})
                               (let [events# (atom []) attempts# (atom [])
                                     save# (fn [] (spit ~evidence (pr-str {:events @events# :attempts @attempts#})))
                                     original# ~'aguafria.zig.compiler-work/run-command!]
                                 (with-redefs [~'aguafria.zig.explain/event!
                                               (fn [event#] (swap! events# conj event#) (save#))
                                               ~'aguafria.zig.compiler-work/run-command!
                                               (fn [command# invoke#]
                                                 (if (= :compiler ((deref (ns-resolve '~'aguafria.zig.compiler-work '~'command-kind)) command#))
                                                   (do (swap! attempts# conj command#) (save#)
                                                       (throw (ex-info "Compiler forbidden in prepared consumer" {})))
                                                   (original# command# invoke#)))]
                                   (require '~'aguafria.zig.native-root-aot-fixture '~'aguafria.zig.native-root-peer-fixture)
                                   (assert (true? (~'aguafria.zig/value ((resolve '~'aguafria.zig.native-root-aot-fixture/library-mode)))))
                                   ;; This peer still has the genuine, contained testing panic.
                                   (assert (= :native-panic
                                              (try ((resolve '~'aguafria.zig.native-root-peer-fixture/ordinary-panic)) nil
                                                   (catch clojure.lang.ExceptionInfo e# (:aguafria/phase (ex-data e#))))))
                                   (save#)
                                   ((resolve '~'aguafria.zig.native-root-aot-fixture/main)))
                                 (throw (ex-info "Authored process exit unexpectedly returned" {})))))]
        (is (= 19 (:exit consumed)) (str (:out consumed) (:err consumed)))
        (when (.isFile (io/file evidence))
          (let [{:keys [events attempts]} (edn/read-string (slurp evidence))]
            (is (empty? attempts))
            (is (some #(= :bundle-cache-hit (:event %)) events))
            (is (not-any? #(#{:compiled :compile-failed :disk-cache-hit} (:event %)) events))))
        (is (.contains (:err consumed) "AUTHORED ROOT: root panic"))))))
