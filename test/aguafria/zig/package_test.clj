(ns aguafria.zig.package-test
  (:require [aguafria.zig.emitter :as emitter]
            [aguafria.zig.package :as package]
            [clojure.test :refer [deftest is]]))

(def ^:private fixture-namespace
  'aguafria.pkg.catalog-fixture)

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
          form ((var-get var) 'io)]
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

