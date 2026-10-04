(ns aguafria.zig.artifact-test
  (:require [aguafria.zig.artifact :as artifact]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileTime]))

(defn- temporary-directory []
  (.toFile (Files/createTempDirectory "aguafria-artifact-key-"
                                      (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- key-for [inputs] (artifact/key-for :native-library inputs))

(deftest machine-data-printing-ignores-session-preferences
  (let [data {:fixture/value [1 2 3 {:quoted "hello \"☔\""}]
              :fixture/type (with-meta 'fixture/Type {:line 42})}
        expected (pr-str data)]
    (binding [*print-length* 1 *print-level* 1 *print-meta* true
              *print-dup* true *print-readably* false *print-namespace-maps* false]
      (is (= expected (artifact/print-data data)))
      (is (= data (edn/read-string (artifact/print-data data)))))))

(deftest unordered-data-has-canonical-identity
  (let [left (array-map :options (array-map :optimize "safe" :flags #{:a :b})
                        :source "pub fn f() void {}")
        right (array-map :source "pub fn f() void {}"
                         :options (array-map :flags (sorted-set-by #(compare %2 %1) :a :b)
                                             :optimize "safe"))]
    (is (= (key-for left) (key-for right)))
    (is (re-matches #"[a-f0-9]{64}" (key-for left)))
    (is (= (key-for left)
           (binding [*print-length* 1 *print-level* 1 *print-meta* true
                     *print-namespace-maps* true *print-readably* false]
             (key-for (with-meta right {:unrelated "editor metadata"})))))))

(deftest semantic-inputs-and-order-remain-significant
  (let [inputs {:source "pub fn f() i32 { return 1; }"
                :compiler "0.16.0" :target "aarch64-macos" :cpu "native"
                :optimize "safe" :dependencies {"lib" "content-A"}
                :flags ["-lfirst" "-lsecond"] :native-test? false}]
    (doseq [[k v] {:source "pub fn f() i32 { return 2; }"
                   :compiler "0.17.0" :target "x86_64-linux" :cpu "baseline"
                   :optimize "debug" :dependencies {"lib" "content-B"}
                   :flags ["-lsecond" "-lfirst"] :native-test? true}]
      (is (not= (key-for inputs) (key-for (assoc inputs k v))) (str k))))
  (doseq [[a b] [[{:x 1} [[:x 1]]] [#{1 2} [1 2]] ['(1 2) [1 2]]
                 ["1" 1] [:x 'x]]]
    (is (not= (key-for a) (key-for b))))
  (is (not= (artifact/key-for :bundle ["module" "hash"])
            (artifact/key-for :bundle-entry ["module" "hash"]))))

(deftest explicit-key-and-abi-versions-invalidate-artifacts
  (let [before (key-for {:source "source"})]
    (doseq [version ['key-version 'native-abi-version]]
      (with-redefs-fn {(ns-resolve 'aguafria.zig.artifact version) 999}
        #(is (not= before (key-for {:source "source"})))))))

(deftest object-content-survives-relocation-and-timestamp-changes
  (let [directory (temporary-directory)
        a (io/file directory "project-a.o")
        b (io/file directory "project-b.o")
        identity #(artifact/argument-identity (str %))]
    (spit a "same object bytes")
    (spit b "same object bytes")
    (let [before (identity a)]
      (is (= before (identity b)))
      (Files/setLastModifiedTime (.toPath a) (FileTime/fromMillis 1000))
      (is (= before (identity a)))
      (testing "A replacement with the same size and mtime still invalidates"
        (spit a "new! object bytes")
        (Files/setLastModifiedTime (.toPath a) (FileTime/fromMillis 1000))
        (is (= (.length a) (.length b)))
        (is (not= before (identity a)))))))

(deftest location-sensitive-inputs-retain-paths
  (let [directory (temporary-directory)]
    (doseq [extension ["zig" "c" "dylib" "so" "dll" "a" "lib"]]
      (let [a (io/file directory (str "a." extension))
            b (io/file directory (str "b." extension))]
        (spit a "identical bytes")
        (spit b "identical bytes")
        (is (not= (artifact/argument-identity (str a))
                  (artifact/argument-identity (str b))) extension))))
  (is (= [:argument "-fPIC"] (artifact/argument-identity "-fPIC"))))

(deftest compiler-options-do-not-reintroduce-object-paths
  (let [directory (temporary-directory)
        a (io/file directory "a.o")
        b (io/file directory "b.o")
        options (fn [file]
                  {:zig-args ["-fPIC" (str file)]
                   :module-zig-args {"dep" [(str file)]}
                   :modules {"dep" "location-sensitive.zig"}})]
    (spit a "object bytes")
    (spit b "object bytes")
    (is (= (key-for (artifact/compiler-options-identity (options a)))
           (key-for (artifact/compiler-options-identity (options b)))))
    (is (= ["-fPIC" (str a)] (:zig-args (options a))) "Compiler command remains unchanged")
    (is (not= (key-for (artifact/compiler-options-identity (options a)))
              (key-for (artifact/compiler-options-identity
                        (assoc (options a) :modules {"dep" "elsewhere.zig"})))))))
