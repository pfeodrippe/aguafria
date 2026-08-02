(ns aguafria.zig.project
  "Project module catalogs used by converted Zig namespaces.

  Catalogs are EDN data, not namespace metadata or generated Clojure source."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io PushbackReader]
           [java.net URL]))

(def ^:private resource-name "aguafria-project.edn")
(defonce ^:private catalogs (atom {}))
(defonce ^:private loaded-resources (atom #{}))
(defonce ^:private resource-lock (Object.))

(def ^:dynamic *catalog-namespace*
  "Optional source namespace used while converter forms are evaluated in a
  temporary REPL namespace. Normal generated namespace loading leaves this nil."
  nil)

(defn register-catalog!
  "Register one serializable project catalog and return it."
  [catalog]
  (when-not (and (map? catalog)
                 (= 1 (:schema-version catalog))
                 (map? (:modules catalog)))
    (throw (ex-info "Invalid Aguafria project catalog"
                    {:catalog catalog :expected-schema-version 1})))
  (swap! catalogs merge (:modules catalog))
  catalog)

(defn ^:no-doc register-module-defaults!
  "Register converter-owned compact defaults for one module without replacing
  rename data that may already have been installed by a tree conversion."
  [module compact-defaults]
  (swap! catalogs update (str module)
         #(assoc (or % {}) :compact-defaults (vec compact-defaults)))
  nil)

(defn load-catalog!
  "Read and register an `aguafria-project.edn` file or URL."
  [source]
  (with-open [reader (PushbackReader. (io/reader source))]
    (register-catalog! (edn/read reader))))

(defn- resource-urls
  []
  (let [loader (.getContextClassLoader (Thread/currentThread))]
    (enumeration-seq (.getResources loader resource-name))))

(defn ensure-resource-catalogs!
  "Discover every `aguafria-project.edn` resource currently on the classpath."
  []
  (locking resource-lock
    (doseq [^URL resource (resource-urls)
            :let [id (str resource)]
            :when (not (contains? @loaded-resources id))]
      (load-catalog! resource)
      (swap! loaded-resources conj id)))
  nil)

(defn declaration-zig-name
  "Resolve a target Clojure Var name to its exact Zig declaration spelling."
  [target-namespace clojure-name]
  (ensure-resource-catalogs!)
  (let [module (get @catalogs (str target-namespace))
        renames (:renames module)
        clojure-name (str clojure-name)]
    (or (get renames clojure-name) clojure-name)))

(defn compact-default?
  "True when a converted top-level declaration intentionally omitted an empty
  `:attrs` set from its Clojure source. This preserves Zig's no-flag semantics
  without making generated declarations display `{:attrs #{}}`."
  [target-namespace clojure-name]
  (ensure-resource-catalogs!)
  (let [target-namespace (or *catalog-namespace* target-namespace)]
    (contains? (set (:compact-defaults (get @catalogs (str target-namespace))))
               (str clojure-name))))

(defn stats
  "Return serializable project-catalog inspection data."
  []
  (ensure-resource-catalogs!)
  {:module-count (count @catalogs)
   :modules (into (sorted-map)
                  (map (fn [[module data]]
                         [module {:rename-count (count (:renames data))
                                  :compact-default-count
                                  (count (:compact-defaults data))}]))
                  @catalogs)})
