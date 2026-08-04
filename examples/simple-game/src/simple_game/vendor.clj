(ns simple-game.vendor
  "Inspect or explicitly update the example's vendored upstream repositories."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [simple-game.build :as build]
            [simple-game.generate :as generate])
  (:import [java.time LocalDate]))

(defn lock-file
  []
  (io/file (:root (build/paths)) "vendor-lock.edn"))

(defn lock-data
  []
  (edn/read-string (slurp (lock-file))))

(defn- git!
  [directory & arguments]
  (let [command (into ["git" "-C" (.getAbsolutePath ^java.io.File directory)]
                      arguments)
        process (.start
                 (doto (ProcessBuilder. ^java.util.List command)
                   (.redirectErrorStream true)))
        output (slurp (.getInputStream process))
        exit (.waitFor process)]
    (when-not (zero? exit)
      (throw (ex-info "Vendor git command failed"
                      {:command command :exit exit :output output})))
    (str/trim output)))

(defn status
  "Return the locked and checked-out commit for every dependency."
  []
  (let [root (:root (build/paths))]
    (into (sorted-map)
          (map (fn [[name {:keys [path] :as descriptor}]]
                 (let [directory (io/file root "vendor"
                                          (or path (clojure.core/name name)))]
                   [name (assoc descriptor
                                :path (.getAbsolutePath directory)
                                :checked-out (git! directory "rev-parse" "HEAD")
                                :clean? (str/blank?
                                         (git! directory "status" "--porcelain")))])))
          (:dependencies (lock-data)))))

(defn update!
  "Update locked checkouts and regenerate every native/browser binding."
  []
  (let [root (:root (build/paths))
        current (lock-data)
        dependencies
        (into (sorted-map)
              (map
               (fn [[name {:keys [branch path] :as descriptor}]]
                 (let [directory (io/file root "vendor"
                                          (or path (clojure.core/name name)))]
                   (when-not (str/blank? (git! directory "status" "--porcelain"))
                     (throw (ex-info "Refusing to update a dirty vendor checkout"
                                     {:dependency name
                                      :path (.getAbsolutePath directory)})))
                   (git! directory "fetch" "origin" branch)
                   (git! directory "checkout" "--detach" (str "origin/" branch))
                   [name (assoc descriptor
                                :commit (git! directory "rev-parse" "HEAD"))]))
               (:dependencies current)))
        updated {:generated-at (str (LocalDate/now))
                 :dependencies dependencies}]
    (with-open [writer (io/writer (lock-file))]
      (pprint/pprint updated writer))
    {:lock (.getAbsolutePath (lock-file))
     :dependencies (status)
     :bindings (generate/summary
                (into (generate/generate!)
                      (generate/generate-web!)))}))

(defn -main
  [& [command]]
  (pprint/pprint
   (case (or command "status")
     "status" (status)
     "update" (update!)
     (throw (ex-info "Unknown vendor command"
                     {:command command :supported ["status" "update"]}))))
  (shutdown-agents))
