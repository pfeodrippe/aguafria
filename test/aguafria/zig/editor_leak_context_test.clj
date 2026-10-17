(ns aguafria.zig.editor-leak-context-test
  "Resource recipe/provenance contracts. These mock compiler/materialization
  boundaries; they are not evidence of a completed strict editor workload."
  (:require [aguafria.zig.emitter :as emitter]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- resource-command-probe [test-context? compile-exit]
  (let [directory (.toFile (Files/createTempDirectory
                            "editor-leak-context-" (make-array FileAttribute 0)))
        output (str (io/file directory "resource.dylib"))
        command ["zig" "build-lib" "-dynamic" (str "-femit-bin=" output)
                 "/bridge/panic-support.dylib" "-lc" "-Odebug" "-Mroot=resource.zig"]
        calls (atom [])]
    (try
      (let [result (with-redefs-fn
                     {#'runtime/run-command
                      (fn [argv cwd]
                        (swap! calls conj {:command argv :directory cwd})
                        {:exit (if (= "test" (second argv)) compile-exit 0)})}
                     #(#'runtime/run-library-command
                       command (.getAbsolutePath directory) test-context?))]
        {:original command :calls @calls :result result
         :runner (when (.exists (io/file directory "jvm_resource_runner.zig"))
                   (slurp (io/file directory "jvm_resource_runner.zig")))})
      (finally
        (io/delete-file (io/file directory "jvm_resource_runner.zig") true)
        (io/delete-file directory true)))))

(deftest ordinary-adapters-do-not-inherit-test-frontend
  (let [{:keys [original calls runner result]} (resource-command-probe false 0)]
    (is (= [original] (mapv :command calls)))
    (is (nil? runner))
    (is (zero? (:exit result)))))

(deftest resource-adapters-use-real-test-frontend-without-executing-tests
  (let [{:keys [calls runner result]} (resource-command-probe true 0)
        [compile-command link-command] (mapv :command calls)]
    (is (= 2 (count calls)))
    (is (= ["zig" "test" "--test-no-exec"] (subvec compile-command 0 3)))
    (is (some #{"--test-runner"} compile-command))
    (is (some #{"-fno-emit-bin"} compile-command))
    (is (some #{"-Mroot=resource.zig"} compile-command))
    (is (not-any? #{"/bridge/panic-support.dylib"} compile-command))
    (is (= "pub fn main() void {}\n" runner))
    (is (= ["zig" "build-lib"] (subvec link-command 0 2)))
    (is (= "/bridge/panic-support.dylib" (last link-command)))
    (is (zero? (:exit result)))))

(deftest failed-resource-compilation-never-links-or-runs-a-body
  (let [{:keys [calls result]} (resource-command-probe true 1)]
    (is (= 1 (count calls)))
    (is (= 1 (:exit result)))
    (is (= (:command (first calls)) (:command result)))))

(deftest resource-publication-is-lazy-and-context-is-owned-by-materialization
  (let [reference {:category :global-const :symbol 'aguafria.std.testing/allocator}
        registrations (atom [])
        materializations (atom [])
        existing-namespaces (set (map ns-name (all-ns)))
        context-name (atom nil)]
    (try
      (with-redefs-fn
        {#'jvm/test-resources (atom {})
         #'emitter/prepare-declaration
         (fn [context descriptor]
           (reset! context-name (ns-name context))
           descriptor)
         #'runtime/register-declaration!
         (fn [descriptor]
           (swap! registrations conj
                  {:descriptor descriptor :source-only? runtime/*source-only-registration?*}))
         #'value/native-value (fn [descriptor realize] {:descriptor descriptor :realize realize})
         #'runtime/materialize-constant!
         (fn [descriptor]
           (swap! materializations conj
                  {:descriptor descriptor :test-context? runtime/*native-test-context?*})
           :materialized)}
        #(binding [runtime/*native-test-context?* false]
           (let [resource (jvm/test-resource! reference)]
             (is (identical? resource (jvm/test-resource! reference)))
             (is (= :test (get-in resource [:descriptor :execution-context])))
             (is (= 1 (count @registrations)))
             (is (true? (:source-only? (first @registrations))))
             (is (empty? @materializations) "Preparing a handle must not realize native state")
             (is (= :materialized ((:realize resource))))
             (is (= [true] (mapv :test-context? @materializations)))
             (is (false? runtime/*native-test-context?*)))))
      (finally
        ;; This probe's fake descriptor never enters the runtime registry.
        (when (and @context-name (not (contains? existing-namespaces @context-name)))
          (remove-ns @context-name))))))
