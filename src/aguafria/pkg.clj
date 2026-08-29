(ns aguafria.pkg
  "Bootstrap EDN-cataloged third-party Zig package namespaces.

  Require this namespace before package namespaces in the same `ns` form.
  No package-specific Clojure source exists: the generated EDN catalog is the
  authority and its declarations are interned as ordinary Vars."
  (:require [aguafria.zig.package :as package]))

(def installation
  "Summary of the package catalogs installed from the current classpath."
  (package/install-resource-catalogs!))

(defn catalog-info
  "Return compact information for every installed package catalog."
  []
  (package/catalog-info))

(defn namespaces
  "Return every installed `aguafria.pkg.*` namespace symbol."
  []
  (package/namespaces))

(defn entries
  "Return package declaration metadata globally or for one namespace."
  ([] (package/entries))
  ([namespace-name] (package/entries namespace-name)))
