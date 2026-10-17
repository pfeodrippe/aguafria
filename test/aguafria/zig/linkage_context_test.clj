(ns aguafria.zig.linkage-context-test
  (:require [aguafria.zig.bundle :as bundle]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(defn- child [form]
  (shell/sh (str (System/getProperty "java.home") "/bin/java")
            "-Xmx768m" "-XX:ActiveProcessorCount=2" "--enable-native-access=ALL-UNNAMED"
            "-cp" (System/getProperty "java.class.path") "clojure.main" "-e" (pr-str form)))

(deftest explicit-linkage-owners-isolate-object-consumers-without-authored-exports
  (let [file (.toFile (Files/createTempFile "aguafria-object-consumer-" ".zig"
                                          (make-array java.nio.file.attribute.FileAttribute 0)))
        object (str (Files/createTempFile "aguafria-companion-" ".o"
                                         (make-array java.nio.file.attribute.FileAttribute 0)))
        command ["zig" "build-lib" "-dynamic" "-femit-bin=unused" "support.dylib" "-Odebug"]
        artifact {:module "fixture" :hash "fixture" :jvm-namespace-image? true
                  :development-panic :shared :development-panic-support-path "support.dylib"
                  :command (into command [object (str "-Mroot=" file)])}]
    (spit file "export fn __aguafria_fixture() void {}\n")
    (spit object "unit-test object input; not passed to the compiler")
    (is (false? (:isolated-linkage? (bundle/candidate artifact))))
    (binding [bundle/*preparation-owner* 'fixture
              bundle/*isolated-linkage-owners* #{'fixture}]
      (is (true? (:isolated-linkage? (bundle/candidate artifact))))
      (is (= [object] (:link-args (bundle/candidate artifact))))
      (is (false? (:isolated-linkage?
                   (bundle/candidate (assoc artifact :command
                                            (conj command (str "-Mroot=" file))))))))))

(deftest failed-isolated-link-does-not-publish-earlier-context-indexes
  (let [a {:module "first" :hash "first" :command ["zig"] :exports []
           :link-args [] :forwarder "" :isolated-linkage? true}
        b (assoc a :module "second" :hash "second")
        collected (atom {:artifacts (into {} (map (juxt #'bundle/artifact-id identity)) [a b])})
        built (atom 0) published (atom 0)]
    (with-redefs-fn {#'bundle/candidate identity
                     #'bundle/build-pack!
                     (fn [_ artifacts _ callbacks]
                       (when (= 2 (swap! built inc))
                         (throw (ex-info "second link rejected" {:exit 1})))
                       ((:publish-index! callbacks) #(swap! published inc))
                       {:handlers (count artifacts) :compiler-invocations 1})}
      #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"second link rejected"
                             (bundle/finish! "unused" collected {}))))
    (is (= 2 @built))
    (is (zero? @published))))

(deftest conflicting-authored-c-exports-retain-their-real-names-and-independent-implementations
  (let [root (str (Files/createTempDirectory "aguafria-linkage-contexts-"
                                             (make-array java.nio.file.attribute.FileAttribute 0)))
        cache (str root "/cache")
        namespaces '[aguafria.zig.linkage-context-first-fixture aguafria.zig.linkage-context-second-fixture]
        prepared (child
                  `(do
                     (require '~'aguafria.zig '~'aguafria.zig.runtime)
                     (~'aguafria.zig/configure! {:cache-dir ~cache})
                     (let [r# (~'aguafria.zig/precompile! {:namespaces '~namespaces :linkage-contexts '~namespaces})]
                       (assert (pos? (get-in r# [:bundles :isolated-linkage-contexts])))
                       (assert (> (count (get-in r# [:bundles :packs])) 1))
                       (assert (empty? (get-in r# [:bundles :standalone])))
                       (doseq [n# '~namespaces]
                         (assert (empty? (get-in (deref (deref (ns-resolve '~'aguafria.zig.runtime '~'registry)))
                                                 [(str n#) :native-generations])))))
                     (shutdown-agents)))]
    (is (zero? (:exit prepared)) (str (:out prepared) (:err prepared)))
    (when (zero? (:exit prepared))
      (let [consumed
            (child
             `(do
                (require '~'aguafria.zig '~'aguafria.zig.compiler-work '~'aguafria.zig.explain)
                (~'aguafria.zig/configure! {:cache-dir ~cache})
                (let [events# (atom []) attempts# (atom 0)
                      original# ~'aguafria.zig.compiler-work/run-command!]
                  (with-redefs [~'aguafria.zig.explain/event! #(swap! events# conj %)
                                ~'aguafria.zig.compiler-work/run-command!
                                (fn [command# invoke#]
                                  (if (= :compiler ((deref (ns-resolve '~'aguafria.zig.compiler-work '~'command-kind)) command#))
                                    (do (swap! attempts# inc) (throw (ex-info "Compiler forbidden" {})))
                                    (original# command# invoke#)))]
                    (doseq [n# '~namespaces] (require n#))
                    (assert (= 11 (~'aguafria.zig/value ((resolve '~'aguafria.zig.linkage-context-first-fixture/foo)))))
                    (assert (= 22 (~'aguafria.zig/value ((resolve '~'aguafria.zig.linkage-context-second-fixture/implementation)))))
                    ;; Exercise the real C ABI too, not only the generated JVM
                    ;; adapters. Both libraries must export the unrenamed foo.
                    (let [paths# (into {} (keep #(when (and (= :bundle-cache-hit (:event %))
                                                            (contains? #{~@(map str namespaces)} (:module %)))
                                                   [(:module %) (:path %)])) @events#)]
                      (assert (= 2 (count (set (vals paths#)))))
                      (doseq [[n# expected#] [[~(str (first namespaces)) 11] [~(str (second namespaces)) 22]]]
                        (with-open [arena# (java.lang.foreign.Arena/ofConfined)]
                          (let [lookup# (java.lang.foreign.SymbolLookup/libraryLookup
                                         (.toPath (java.io.File. (get paths# n#))) arena#)
                                address# (.orElseThrow (.find lookup# "foo"))
                                handle# (.downcallHandle (java.lang.foreign.Linker/nativeLinker) address#
                                                         (java.lang.foreign.FunctionDescriptor/of
                                                          java.lang.foreign.ValueLayout/JAVA_INT
                                                          (make-array java.lang.foreign.MemoryLayout 0))
                                                         (make-array java.lang.foreign.Linker$Option 0))]
                            (assert (= expected# (.invokeWithArguments handle# (object-array 0)))))))))
                  (assert (zero? @attempts#))
                  (assert (not-any? #(#{:compiled :compile-failed :disk-cache-hit} (:event %)) @events#)))
                (shutdown-agents)))]
        (is (zero? (:exit consumed)) (str (:out consumed) (:err consumed)))))))
