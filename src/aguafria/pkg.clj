(ns aguafria.pkg
  "Bootstrap EDN-cataloged third-party Zig package namespaces.

  Optional eager installation and catalog inspection. Prepared `aguafria.pkg.*`
  namespaces can be required directly. The EDN catalog remains the authority;
  generated entry points only make each namespace discoverable."
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
