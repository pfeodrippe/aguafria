(ns aguafria.zig.artifact-test
  (:require [aguafria.zig.artifact :as artifact]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileTime]
           [java.security MessageDigest]
           [java.util Date HexFormat Random UUID]))

(defn- temporary-directory []
  (.toFile (Files/createTempDirectory "aguafria-artifact-key-"
                                      (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- key-for [inputs] (artifact/key-for :native-library inputs))

(defrecord KeyFixture [source options])

(defn- legacy-canonical [value]
  (cond
    (record? value) [:record (.getName (class value)) (legacy-canonical (into {} value))]
    (map? value) [:map (->> value
                            (map (fn [[k v]] [(legacy-canonical k) (legacy-canonical v)]))
                            (sort-by (comp pr-str first)) vec)]
    (set? value) [:set (->> value (map legacy-canonical) (sort-by pr-str) vec)]
    (vector? value) [:vector (mapv legacy-canonical value)]
    (sequential? value) [:sequence (mapv legacy-canonical value)]
    :else [:scalar value]))

(defn- legacy-key [domain value]
  (binding [*print-length* nil *print-level* nil *print-meta* false
            *print-dup* false *print-readably* true *print-namespace-maps* false]
    (.formatHex (HexFormat/of)
                (.digest (MessageDigest/getInstance "SHA-256")
                         (.getBytes (pr-str [1 2 domain (legacy-canonical value)]) "UTF-8")))))

(deftest optimized-printer-preserves-existing-key-bytes
  (let [unicode (String. (char-array (map char (range 65536))))
        scalars [nil true false "" "\"\\\n\t\r\b\f" unicode
                 0 -1 1N 2.50M 2/3 1.5 (float 1.5) Double/NaN
                 Double/POSITIVE_INFINITY Double/NEGATIVE_INFINITY
                 \newline \space \☔ :some.ns/key 'some.ns/symbol
                 (UUID/fromString "12345678-1234-1234-1234-123456789abc") (Date. 42)]
        values (concat scalars [scalars (apply list scalars) #{:z :a :m}
                                {:source unicode :options {:cpu :native :flags #{"-lc" "-Odebug"}}}
                                (->KeyFixture unicode {:target "aarch64-macos"})])]
    (doseq [value values]
      (is (= (legacy-key :native-library value) (key-for value))
          (str "Preserves key for " (type value))))
    (let [random (Random. 42)]
      (dotimes [index 100]
        (let [source (String. (char-array (repeatedly 1000 #(char (.nextInt random 65536)))))
              data {:source source :domain index :args [source nil index]
                    :flags (set (take (mod index 5) [:a :b :c :d]))}]
          (is (= (legacy-key :native-library data) (key-for data))))))))

(deftest source-cache-uses-exact-content-domain-and-versions
  (let [cache (artifact/source-key-cache)]
    (binding [artifact/*source-key-cache* cache]
      (is (= (.hashCode "Aa") (.hashCode "BB")) "Control has colliding Java hashCodes")
      (is (not= (artifact/key-for :bundle-source "Aa") (artifact/key-for :bundle-source "BB")))
      (is (= (legacy-key :bundle-source "Aa") (artifact/key-for :bundle-source (String. "Aa"))))
      (is (not= (artifact/key-for :bundle-source "Aa") (artifact/key-for :other "Aa")))
      (doseq [version ['key-version 'native-abi-version]]
        (let [before (artifact/key-for :bundle-source "Aa")]
          (with-redefs-fn {(ns-resolve 'aguafria.zig.artifact version) 999}
            #(is (not= before (artifact/key-for :bundle-source "Aa"))))))
      (is (= 5 (:misses (artifact/source-key-statistics cache))))
      (is (= 5 (:retained-entries (artifact/source-key-statistics cache)))))))

(deftest source-cache-remains-bounded-and-evicts-least-recently-used
  (let [cache (artifact/source-key-cache 2 400)]
    (binding [artifact/*source-key-cache* cache]
      (doseq [value ["Aa" "BB" "Aa" "CC" "BB"]]
        (is (= (legacy-key :bundle-source value) (artifact/key-for :bundle-source value))))
      (is (= {:hits 1 :misses 4 :evictions 2 :retained-bytes 264 :retained-entries 2}
             (artifact/source-key-statistics cache)))))
  (let [cache (artifact/source-key-cache 100 160)]
    (binding [artifact/*source-key-cache* cache]
      (doseq [value ["0123456789" "abcdefghij" (apply str (repeat 40 "z"))]]
        (is (= (legacy-key :bundle-source value) (artifact/key-for :bundle-source value))))
      (is (= {:hits 0 :misses 3 :evictions 1 :retained-bytes 148 :retained-entries 1}
             (artifact/source-key-statistics cache)))))
  (is (thrown? clojure.lang.ExceptionInfo (artifact/source-key-cache 0 100))))

(deftest source-cache-is-safe-across-preparation-workers
  (let [cache (artifact/source-key-cache)
        source (apply str (repeat 100 "source\n"))
        expected (legacy-key :bundle-source source)]
    (binding [artifact/*source-key-cache* cache]
      (let [jobs (mapv (fn [_] (future (artifact/key-for :bundle-source (String. source)))) (range 20))]
        (is (every? #(= expected @%) jobs))))
    (is (= 1 (:misses (artifact/source-key-statistics cache))))
    (is (= 19 (:hits (artifact/source-key-statistics cache))))))

(defmethod print-method ::custom [value writer]
  (.write ^java.io.Writer writer "#fixture/custom ")
  (print-method (vec value) writer))

(deftest machine-data-printer-preserves-general-printer-contract
  (let [unicode (String. (char-array (map char (range 65536))))
        values [unicode nil [] '() #{} {} ["hello\n" 42 :key 'a.b/c]
                {:a/b "quote\"" :a/c "backslash\\"}
                {'a/b ["hello\t"] 'a/c '(false nil)}
                {:a/b 1 :c/d 2} {:a/b 1 :unqualified 2} {"key" 1 :a/b 2}
                (sorted-map :a 1 :b 2) (sorted-set :c :a :b) (range 4)
                (->KeyFixture unicode {:a/b "value"})
                (with-meta ["custom" 42] {:type ::custom})
                (java.util.ArrayList. ["hello\n" 42])
                (java.util.LinkedHashMap. {"key" "value\n"})
                (conj clojure.lang.PersistentQueue/EMPTY "hello\n")
                (Date. 42) Double/NaN]]
    (doseq [namespace-maps? [true false] value values]
      (let [expected (binding [*print-length* nil *print-level* nil *print-meta* false
                               *print-dup* false *print-readably* true
                               *print-namespace-maps* namespace-maps?]
                       (pr-str value))]
        (is (= expected (artifact/print-data value namespace-maps?))
            (str "Preserves general printer for " (type value)))))))

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
