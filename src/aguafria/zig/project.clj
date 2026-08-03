(ns aguafria.zig.project
  "Project module catalogs used by converted Zig namespaces.

  Catalogs are EDN data, not namespace metadata or generated Clojure source."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io PushbackReader]
           [java.net URL]
           [java.nio.file CopyOption Files StandardCopyOption]))

(def ^:private resource-name "aguafria-project.edn")
(defonce ^:private catalogs (atom {}))
(defonce ^:private loaded-resources (atom #{}))
(defonce ^:private loaded-source-catalogs (atom #{}))
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
    (let [catalog-url (io/as-url source)
          base-url (str (URL. catalog-url "."))
          catalog (edn/read reader)]
      (register-catalog!
       (update catalog :modules
               (fn [modules]
                 (into {}
                       (map (fn [[module data]]
                              [module (assoc data :catalog-base-url base-url)]))
                       modules)))))))

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

(defn ensure-source-catalog!
  "Discover the nearest generated-project catalog above a loaded Clojure
  source file. This makes evaluating one generated namespace with `load-file`
  behave like putting the generated root on the classpath; users do not need
  to register project metadata manually."
  [source-file]
  (when (and (string? source-file) (not (str/blank? source-file)))
    (let [source (io/file source-file)
          start (if (.isDirectory source) source (.getParentFile source))]
      (when start
        (when-let [catalog
                   (some (fn [directory]
                           (let [candidate (io/file directory resource-name)]
                             (when (.isFile candidate) candidate)))
                         (take-while some? (iterate #(.getParentFile %) start)))]
          (let [id (.getCanonicalPath catalog)]
            (locking resource-lock
              (when-not (contains? @loaded-source-catalogs id)
                (load-catalog! catalog)
                (swap! loaded-source-catalogs conj id))))))))
  nil)

(defn ^:no-doc converted-module?
  [module]
  (ensure-resource-catalogs!)
  (contains? @catalogs (str module)))

(defn ^:no-doc module-data
  [module]
  (ensure-resource-catalogs!)
  (get @catalogs (str module)))

(defn ^:no-doc module-relative-path
  "Return the converted module's project-relative Zig path, when known."
  [module]
  (:relative-path (module-data module)))

(defn- url-relative
  [base relative]
  (URL. (URL. base) (str/replace (str relative) "\\" "/")))

(defn- extracted-build-path-root
  [{:keys [token bundle-relative]}]
  (io/file (or (System/getProperty "aguafria.cache-dir") ".aguafria/zig")
           "project-build-paths"
           (subs token 2 (- (count token) 2))
           (.getName (io/file bundle-relative))))

(defn- copy-resource!
  [^URL source ^java.io.File target executable?]
  (io/make-parents target)
  (with-open [input (.openStream source)]
    (Files/copy input (.toPath target)
                (into-array CopyOption
                            [StandardCopyOption/REPLACE_EXISTING])))
  (when executable?
    (.setExecutable target true false))
  target)

(defn- resolve-bundled-build-path
  [catalog-base-url {:keys [bundle-relative directory? entries] :as descriptor}]
  (when-not catalog-base-url
    (throw (ex-info "Build-option path catalog has no resource base"
                    {:aguafria/phase :zig-build-option-path
                     :descriptor descriptor})))
  (let [bundle-url (url-relative catalog-base-url bundle-relative)]
    (if (= "file" (.getProtocol bundle-url))
      (let [file (.getCanonicalFile (io/file bundle-url))]
        (when-not (.exists file)
          (throw (ex-info "Bundled Zig build-option path is missing"
                          {:aguafria/phase :zig-build-option-path
                           :path (.getAbsolutePath file)
                           :descriptor descriptor})))
        (.getAbsolutePath file))
      (let [target-root (extracted-build-path-root descriptor)]
        (if directory?
          (do
            (.mkdirs target-root)
            (doseq [{:keys [relative executable?]} entries]
              (copy-resource!
               (url-relative catalog-base-url
                             (str bundle-relative "/" relative))
               (io/file target-root relative)
               executable?)))
          (let [{:keys [executable?]} (first entries)]
            (copy-resource! bundle-url target-root executable?)))
        (.getAbsolutePath (.getCanonicalFile target-root))))))

(defn ^:no-doc generated-modules
  "Return build-generated named Zig module sources captured for this module."
  [module]
  (let [{:keys [generated-modules catalog-base-url]} (module-data module)]
    (into (sorted-map)
          (map (fn [[module-name source]]
                 [module-name
                  (if (string? source)
                    source
                    (let [{:keys [source-template paths]} source]
                      (reduce
                       (fn [rendered {:keys [token] :as path}]
                         (str/replace rendered token
                                      (let [resolved
                                            (resolve-bundled-build-path
                                             catalog-base-url path)
                                            quoted (pr-str resolved)]
                                        (subs quoted 1 (dec (count quoted))))))
                       source-template
                       paths)))]))
          (or generated-modules {}))))

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

(defn ^:no-doc module-import
  "Return converter-owned import placement data for one Clojure namespace
  alias. This keeps source ordering in the EDN catalog instead of decorating
  every generated declaration with presentation metadata."
  [source-namespace alias]
  (ensure-resource-catalogs!)
  (get-in @catalogs [(str (or *catalog-namespace* source-namespace))
                     :imports (str alias)]))

(defn ^:no-doc declaration-source-order
  "Return the original Zig root-declaration position for a converted Var."
  [source-namespace declaration-name]
  (ensure-resource-catalogs!)
  (get-in @catalogs [(str (or *catalog-namespace* source-namespace))
                     :source-orders (str declaration-name)]))

(defn ^:no-doc expected-declaration-count
  "Return the converter-recorded number of top-level declarations for module."
  [module]
  (when-let [source-orders (:source-orders (module-data module))]
    (count source-orders)))

(defn stats
  "Return serializable project-catalog inspection data."
  []
  (ensure-resource-catalogs!)
  {:module-count (count @catalogs)
   :modules (into (sorted-map)
                  (map (fn [[module data]]
                         [module {:relative-path (:relative-path data)
                                  :rename-count (count (:renames data))
                                  :compact-default-count
                                  (count (:compact-defaults data))
                                  :import-count (count (:imports data))
                                  :generated-module-count
                                  (count (:generated-modules data))
                                  :source-order-count
                                  (count (:source-orders data))}]))
                  @catalogs)})
