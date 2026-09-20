(ns aguafria.zig.package-test
  (:require [aguafria.zig.emitter :as emitter]
            [aguafria.zig.package :as package]
            [aguafria.zig.prepare :as prepare]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [aguafria.zig :as az]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private fixture-namespace
  'aguafria.pkg.catalog-fixture)

(deftest catalog-understands-canonical-function-declarations-test
  (let [public (#'package/declaration-parts
                '(az/defn serialize [:array 36 :u8]
                   "Serialize a UUID."
                   {:zig/name "serialize", :attrs #{:public}}
                   [[uuid Uuid]]
                   uuid))
        default-public (#'package/declaration-parts '(az/defn ready :bool [] true))
        private (#'package/declaration-parts '(az/defn- helper :u8 [] 0))]
    (is (#'package/public-declaration? public))
    (is (#'package/public-declaration? default-public))
    (is (false? (#'package/public-declaration? private)))
    (is (= "Serialize a UUID." (:documentation public)))
    (is (= "serialize" (#'package/declaration-zig-name public)))
    (is (= 1 (#'package/function-param-count public)))
    (is (= 0 (#'package/function-param-count default-public)))))

(defn- forget-fixture!
  []
  (when (find-ns fixture-namespace)
    (remove-ns fixture-namespace))
  (when-let [loaded-libs (some-> (ns-resolve 'clojure.core '*loaded-libs*)
                                 var-get)]
    (dosync (alter loaded-libs disj fixture-namespace))))

(deftest edn-catalog-installs-ordinary-documented-vars-test
  (forget-fixture!)
  (try
    (is (= {:member-count 1
            :namespace-count 1
            :package-count 0}
           (package/install-catalog!
            {:schema-version 1
             :packages {}
             :namespaces
             [{:name fixture-namespace
               :members
               [{:category :function
                 :clojure-name "v4-new"
                 :documentation "Create a UUID."
                 :package "fixture"
                 :param-count 1
                 :signature "pub fn new(io: std.Io) Uuid"
                 :source "src/v4.zig"
                 :symbol 'aguafria.pkg.catalog-fixture/v4-new
                 :zig-alias "fixture_pkg"
                 :zig-name "v4.new"}]}]})))
    (let [var (ns-resolve fixture-namespace 'v4-new)
          form '(aguafria.pkg.catalog-fixture/v4-new io)]
      (is (var? var))
      (is (= "v4.new" (:zig/name (meta var))))
      (is (= "pub fn new(io: std.Io) Uuid"
             (:zig/signature (meta var))))
      (is (re-find #"Create a UUID" (:doc (meta var))))
      (is (= '(aguafria.pkg.catalog-fixture/v4-new io) form))
      (is (= "fixture_pkg.v4.new(io)"
             (emitter/emit-expr (the-ns fixture-namespace) form))))
    (finally
      (forget-fixture!))))

(deftest prepared-library-alias-fields-work-from-jvm-and-native-code
  (let [prefix 'aguafria.pkg.type-fields-fixture
        root (.getCanonicalPath (io/file "test/fixtures/type_catalog.zig"))
        catalog (#'package/package-catalog
                 "type_fixture" {:namespace-prefix prefix :zig-alias "type_fixture"}
                 {:root-path root :package-root (.getCanonicalPath (io/file "test/fixtures"))})
        original-config (runtime/configuration)
        generated (.getCanonicalPath
                   (.toFile (java.nio.file.Files/createTempDirectory
                             "aguafria-type-fields-" (make-array java.nio.file.attribute.FileAttribute 0))))
        call (fn [suffix member & args]
               (apply (ns-resolve (symbol (str prefix suffix)) member) args))]
    (try
      (runtime/configure! {:async? false :modules {"type_fixture" root}})
      (package/install-catalog! (assoc catalog :schema-version 1 :packages {}))
      (prepare/write-entrypoints! {:kind :packages :namespaces (:namespaces catalog)
                                   :generated-dir generated})
      (doseq [suffix [".Bytes" ".BytesAlias" ".Buffer.Slice" ".RowAlias.Slice"]]
        (is (= 3 (call suffix '-len "abc")))
        (is (value/zig-pointer? (call suffix '-ptr "abc")))
        (is (= '([self]) (:arglists (meta (ns-resolve (symbol (str prefix suffix)) '-len))))))
      (is (= 42 (call ".RowAlias" '-value (call "" 'row))))
      (is (= "abc" (call ".Buffer" '-items (call "" 'buffer))))
      (is (str/includes? (:doc (meta (ns-resolve prefix 'Buffer))) "Fields:"))
      (is (str/includes? (:doc (meta (ns-resolve (symbol (str prefix ".Buffer")) '-items)))
                         "items: aguafria.pkg.type-fields-fixture.Buffer/Slice"))
      (is (str/includes? (slurp (io/file generated "aguafria/pkg/type_fields_fixture/Buffer.clj"))
                         "items: aguafria.pkg.type-fields-fixture.Buffer/Slice"))
      (binding [*ns* (the-ns prefix)]
        (alias 'az 'aguafria.zig)
        (alias 'slice (symbol (str prefix ".BytesAlias")))
        (eval '(az/defn native-length :usize [[input [:slice-const :u8]]]
                 (slice/-len input)))
        (is (= 3 ((ns-resolve prefix 'native-length) "abc")))
        (is (= "input.len" (emitter/emit-expr *ns* '(slice/-len input)))))
      (finally
        (runtime/configure! original-config)
        (doseq [{:keys [name]} (:namespaces catalog)]
          (remove-ns name)
          (dosync (alter @#'clojure.core/*loaded-libs* disj name)))))))
