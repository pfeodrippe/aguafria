(ns aguafria.zig.prepare-test
  (:require [aguafria.zig.prepare :as prepare]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]))

(defn- directory []
  (let [parent (io/file ".aguafria/prepare-tests")]
    (.mkdirs parent)
    (.toFile (Files/createTempDirectory (.toPath parent) "case-"
                                        (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest structural-type-fields-do-not-guess
  (is (= ["len" "ptr"]
         (mapv :field-name (prepare/type-fields '(type [:slice-const :u8])))))
  (is (= ["len"] (mapv :field-name (prepare/type-fields [:array 3 :u8]))))
  (is (= ["len" "ptr"]
         (mapv :field-name
               (prepare/type-fields
                '(az/if-capture {:payload [a]} alignment
                               (type [:pointer {:size :slice :align a} T])
                               (type [:slice T]))))))
  (is (empty? (prepare/type-fields '(if condition (type [:slice :u8]) :u32))))
  (is (empty? (prepare/type-fields '(unknown-type-constructor :u8)))))

(deftest cyclic-type-aliases-never-invent-getters
  (let [source [{:name 'aguafria.pkg.cyclic
                 :members [{:symbol 'aguafria.pkg.cyclic/A :clojure-name "A"
                            :type-expression 'B :zig-name "A"}
                           {:symbol 'aguafria.pkg.cyclic/B :clojure-name "B"
                            :type-expression 'A :zig-name "B"}]}]]
    (is (= ['aguafria.pkg.cyclic] (mapv :name (prepare/enrich-namespaces source))))))

(defn- prepare! [root & names]
  (prepare/write-entrypoints!
   {:generated-dir (str root)
    :kind :packages
    :namespaces (mapv #(hash-map :name %) names)}))

(deftest prepare-add-change-remove-test
  (let [root (directory)
        first-file (io/file root "aguafria/pkg/first.clj")
        second-file (io/file root "aguafria/pkg/second.clj")
        unrelated (io/file root "keep.txt")]
    (spit unrelated "user data")
    (is (= 1 (:namespace-count (prepare! root 'aguafria.pkg.first))))
    (let [mtime (.lastModified first-file)]
      (prepare! root 'aguafria.pkg.first)
      (is (= mtime (.lastModified first-file)) "unchanged source is not rewritten"))
    (is (= 2 (:namespace-count (prepare! root 'aguafria.pkg.first 'aguafria.pkg.second))))
    (is (.isFile second-file))
    (is (= 1 (:removed-count (prepare! root 'aguafria.pkg.second))))
    (is (not (.exists first-file)))
    (is (= 1 (:removed-count (prepare! root))))
    (is (not (.exists second-file)))
    (is (= "user data" (slurp unrelated)))))

(deftest prep-refuses-edited-and-unowned-files-test
  (doseq [remove? [false true]]
    (let [root (directory)
          file (io/file root "aguafria/pkg/first.clj")]
      (prepare! root 'aguafria.pkg.first)
      (spit file "; edited by user\n" :append true)
      (let [edited (slurp file)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"edited/non-generated"
                             (if remove? (prepare! root) (prepare! root 'aguafria.pkg.first))))
        (is (= edited (slurp file))))))
  (let [root (directory)
        file (io/file root "aguafria/pkg/first.clj")]
    (io/make-parents file)
    (spit file "user data")
    (is (thrown? clojure.lang.ExceptionInfo (prepare! root 'aguafria.pkg.first)))
    (is (= "user data" (slurp file)))))

(deftest prep-validates-all-targets-before-writing-test
  (let [root (directory)
        old (io/file root "aguafria/pkg/old.clj")
        new (io/file root "aguafria/pkg/new.clj")]
    (prepare! root 'aguafria.pkg.old)
    (spit old "edited")
    (is (thrown? clojure.lang.ExceptionInfo (prepare! root 'aguafria.pkg.new)))
    (is (not (.exists new)))
    (is (= "edited" (slurp old)))))

(deftest prep-refuses-unsafe-paths-and-collisions-test
  (let [root (directory)]
    (doseq [namespace-name ['other.pkg.foo 'aguafria.pkg.foo/bar
                          (symbol "aguafria.pkg...escape")]]
      (is (thrown? clojure.lang.ExceptionInfo (prepare! root namespace-name))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"colliding"
                         (prepare! root 'aguafria.pkg.my-lib 'aguafria.pkg.my_lib)))
    (prepare! root 'aguafria.pkg.first)
    (let [manifest (io/file root ".aguafria-packages-entrypoints.edn")
          content (edn/read-string (slurp manifest))]
      (spit manifest (pr-str (assoc-in content [:files "../escape.clj"] "fake")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsafe"
                           (prepare! root 'aguafria.pkg.second)))
      (is (not (.exists (io/file root "aguafria/pkg/second.clj")))))))

(deftest prep-refuses-symlink-output-test
  (let [root (directory)
        outside (directory)
        link (.toPath (io/file root "aguafria"))]
    (Files/createSymbolicLink link (.toAbsolutePath (.toPath outside))
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"symlink"
                         (prepare! root 'aguafria.pkg.first)))
    (is (empty? (seq (.listFiles outside))))))

(defn- fresh-clojure [root code]
  (let [classpath (str (.getAbsolutePath (io/file root)) java.io.File/pathSeparator
                       (System/getProperty "java.class.path"))
        process (.start (doto (ProcessBuilder.
                               ^java.util.List
                               [(str (System/getProperty "java.home") "/bin/java")
                                "--enable-native-access=ALL-UNNAMED"
                                "-cp" classpath "clojure.main" "-e" code])
                          (.redirectErrorStream true)))
        output (slurp (.getInputStream process))]
    {:exit (.waitFor process) :output output}))

(deftest prepared-package-direct-require-and-catalog-update-test
  (let [root (directory)
        catalog-file (io/file root "aguafria/zig-packages.edn")
        catalog {:schema-version 1 :packages {}
                 :namespaces [{:name 'aguafria.pkg.prepared-fixture
                               :members [{:category :constant
                                          :clojure-name "answer"
                                          :package "fixture"
                                          :source "root.zig"
                                          :symbol 'aguafria.pkg.prepared-fixture/answer
                                          :zig-alias "fixture_pkg"
                                          :zig-name "answer"
                                          :signature "pub const answer = 42;"}]}]}]
    (prepare! root 'aguafria.pkg.prepared-fixture)
    (spit catalog-file (pr-str catalog))
    (testing "direct require in a fresh process, no parent/root bootstrap"
      (let [{:keys [exit output]}
            (fresh-clojure
             root
             (str "(require '[aguafria.pkg.prepared-fixture :as fixture])"
                  "(assert (nil? (find-ns 'aguafria.pkg)))"
                  "(assert (= \"pub const answer = 42;\" (:zig/signature (meta #'fixture/answer))))"
                  "(def original #'fixture/answer)"
                  "(require 'aguafria.pkg.prepared-fixture :reload)"
                  "(assert (identical? original #'fixture/answer))"
                  "(print :direct-require-ok)"))]
        (is (zero? exit) output)
        (is (re-find #":direct-require-ok" output))))
    (testing "a changed catalog updates metadata without duplicating declarations in source"
      (spit catalog-file (pr-str (assoc-in catalog [:namespaces 0 :members 0 :signature]
                                          "pub const answer = 43;")))
      (prepare! root 'aguafria.pkg.prepared-fixture)
      (let [{:keys [exit output]}
            (fresh-clojure root
                           (str "(require '[aguafria.pkg.prepared-fixture :as fixture])"
                                "(assert (= \"pub const answer = 43;\" (:zig/signature (meta #'fixture/answer))))"
                                "(print :updated)"))]
        (is (zero? exit) output)
        (is (re-find #":updated" output))))))
