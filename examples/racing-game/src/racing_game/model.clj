(ns racing-game.model
  "Download and verify release model assets; never used by the native runtime."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.math BigInteger]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]))

(defn project-root
  []
  (loop [directory (.getCanonicalFile (io/file (System/getProperty "user.dir")))]
    (cond
      (and (.isFile (io/file directory "deps.edn"))
           (.isDirectory (io/file directory "src/racing_game"))) directory
      (.getParentFile directory) (recur (.getParentFile directory))
      :else (throw (ex-info "Could not locate examples/racing-game"
                            {:user-dir (System/getProperty "user.dir")})))))

(defn manifest
  []
  (-> "models.edn" io/resource slurp edn/read-string))

(defn model-entry
  ([] (model-entry (:default (manifest))))
  ([model]
   (or (get-in (manifest) [:models model])
       (throw (ex-info "Unknown racing model"
                       {:model model
                        :available (sort (keys (:models (manifest))))})))))

(defn model-file
  ([] (model-file (:default (manifest))))
  ([model]
   (io/file (project-root) "resources/models" (:filename (model-entry model)))))

(defn action-head-entry
  []
  (or (:action-head (manifest))
      (throw (ex-info "The racing action head is missing from models.edn" {}))))

(defn action-head-file
  []
  (io/file (project-root) "resources/models"
           (:filename (action-head-entry))))

(defn sha256
  [file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input (io/input-stream file)]
      (let [buffer (byte-array (* 1024 1024))]
        (loop []
          (let [count (.read input buffer)]
            (when (pos? count)
              (.update digest buffer 0 count)
              (recur))))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn verify!
  ([] (verify! (:default (manifest))))
  ([model]
   (let [{:keys [bytes sha256] :as entry} (model-entry model)
         file (model-file model)
         actual-bytes (when (.isFile file) (.length file))
         actual-sha256 (when (= bytes actual-bytes) (racing-game.model/sha256 file))]
     (when-not (and (= bytes actual-bytes) (= sha256 actual-sha256))
       (throw (ex-info "Racing model asset is missing or does not match its manifest"
                       {:model model :file (str file)
                        :expected-bytes bytes :actual-bytes actual-bytes
                        :expected-sha256 sha256 :actual-sha256 actual-sha256})))
     (assoc entry :model model :file file :verified? true))))

(defn verify-action-head!
  []
  (let [{:keys [bytes sha256] :as entry} (action-head-entry)
        file (action-head-file)
        actual-bytes (when (.isFile file) (.length file))
        actual-sha256 (when (= bytes actual-bytes)
                        (racing-game.model/sha256 file))]
    (when-not (and (= bytes actual-bytes) (= sha256 actual-sha256))
      (throw (ex-info "Racing action head is missing or does not match its manifest"
                      {:file (str file)
                       :expected-bytes bytes :actual-bytes actual-bytes
                       :expected-sha256 sha256 :actual-sha256 actual-sha256})))
    (assoc entry :file file :verified? true)))

(defn verify-assets!
  []
  {:model (verify!)
   :action-head (verify-action-head!)})

(defn fetch!
  "Fetch one pinned public model with curl, then atomically install after SHA-256 verification."
  ([] (fetch! (:default (manifest))))
  ([model]
   (let [{:keys [url]} (model-entry model)
         output (model-file model)
         partial (io/file (str output ".part"))]
     (io/make-parents output)
     (when-not (.isFile output)
       (let [process (-> (ProcessBuilder. ["curl" "-fL" "--retry" "3"
                                           "--continue-at" "-"
                                           "--output" (str partial) url])
                         (.inheritIO)
                         (.start))
             exit (.waitFor process)]
         (when-not (zero? exit)
           (throw (ex-info "Model download failed"
                           {:model model :url url :exit exit})))
         (let [{:keys [bytes sha256]} (model-entry model)
               actual-bytes (.length partial)
               actual-sha256 (racing-game.model/sha256 partial)]
           (when-not (and (= bytes actual-bytes) (= sha256 actual-sha256))
             (throw (ex-info "Downloaded model failed verification"
                             {:model model :partial (str partial)
                              :expected-bytes bytes :actual-bytes actual-bytes
                              :expected-sha256 sha256 :actual-sha256 actual-sha256})))
         (Files/move (.toPath partial) (.toPath output)
                     (into-array StandardCopyOption
                                 [StandardCopyOption/ATOMIC_MOVE])))))
     (verify! model))))
