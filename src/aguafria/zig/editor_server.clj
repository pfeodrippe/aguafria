(ns aguafria.zig.editor-server
  "Extension-owned Aguafria nREPL server lifecycle."
  (:require [aguafria.zig.editor :as editor]
            [aguafria.zig.nrepl :as aguafria-nrepl]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [nrepl.server :as nrepl])
  (:import [java.nio.charset StandardCharsets]
           [java.nio ByteBuffer]
           [java.nio.channels FileChannel OverlappingFileLockException]
           [java.nio.file AtomicMoveNotSupportedException CopyOption Files
            StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute PosixFilePermission]
           [java.security MessageDigest]
           [java.util HexFormat UUID]))

(defn- sha256
  [value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes (str value) StandardCharsets/UTF_8))
    (.formatHex (HexFormat/of) (.digest digest))))

(defn- authentication-required?
  [project]
  (let [configured (get-in project [:configuration :authentication?] ::unset)
        environment (some-> (System/getenv "AGUAFRIA_EDITOR_AUTH")
                            str/trim
                            str/lower-case)]
    (cond
      (not= ::unset configured) (boolean configured)
      (some? environment) (not (contains? #{"0" "false" "no" "off"} environment))
      :else false)))

(defn- atomic-write!
  [file content]
  (io/make-parents file)
  (let [target (.toPath ^java.io.File file)
        temporary (Files/createTempFile (.getParent target) ".aguafria-" ".tmp"
                                        (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (Files/writeString temporary (str content) StandardCharsets/UTF_8
                         (into-array StandardOpenOption
                                     [StandardOpenOption/TRUNCATE_EXISTING
                                      StandardOpenOption/WRITE]))
      (try
        (Files/move temporary target
                    (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING
                                            StandardCopyOption/ATOMIC_MOVE]))
        (catch AtomicMoveNotSupportedException _
          (Files/move temporary target
                      (into-array CopyOption
                                  [StandardCopyOption/REPLACE_EXISTING]))))
      (finally
        (Files/deleteIfExists temporary))))
  file)

(defn- protect-token!
  [file]
  (try
    (Files/setPosixFilePermissions
     (.toPath ^java.io.File file)
     #{PosixFilePermission/OWNER_READ PosixFilePermission/OWNER_WRITE})
    (catch UnsupportedOperationException _ nil))
  file)

(defn- delete-owned-file!
  [file expected]
  (when (and (.isFile ^java.io.File file)
             (= (str expected) (slurp file)))
    (Files/deleteIfExists (.toPath ^java.io.File file))))

(defn- acquire-workspace-lock!
  "Own one editor runtime per canonical workspace across JVM processes."
  [workspace]
  (let [lock-file (io/file workspace ".aguafria" "editor.lock")
        _ (io/make-parents lock-file)
        channel (FileChannel/open
                 (.toPath ^java.io.File lock-file)
                 (into-array StandardOpenOption
                             [StandardOpenOption/CREATE
                              StandardOpenOption/READ
                              StandardOpenOption/WRITE]))
        lock (try
               (.tryLock channel)
               (catch OverlappingFileLockException _ nil))]
    (when-not lock
      (.close channel)
      (throw
       (ex-info
        "Another Aguafria editor runtime already owns this workspace"
        {:aguafria/phase :zig-editor-workspace-owned
         :workspace-root (.getAbsolutePath ^java.io.File workspace)
         :owner (when (.isFile ^java.io.File lock-file)
                  (slurp lock-file))})))
    (let [content
          (str "{:jvm-pid " (.pid (java.lang.ProcessHandle/current))
               " :workspace-root " (pr-str (.getAbsolutePath ^java.io.File workspace))
               " :locked-at-ms " (System/currentTimeMillis) "}\n")
          bytes (.getBytes content StandardCharsets/UTF_8)]
      (.truncate channel 0)
      (.position channel 0)
      (loop [buffer (ByteBuffer/wrap bytes)]
        (when (.hasRemaining buffer)
          (.write channel buffer)
          (recur buffer)))
      (.force channel true)
      (protect-token! lock-file)
      {:file lock-file
       :channel channel
       :lock lock
       :content content})))

(defn- release-workspace-lock!
  [{:keys [channel lock]}]
  (when (and lock (.isValid lock)) (.release lock))
  (when (and channel (.isOpen channel)) (.close channel))
  nil)

(defn start!
  "Start the editor nREPL server and publish project-local discovery files."
  ([workspace-root] (start! workspace-root {}))
  ([workspace-root overrides]
   (let [workspace (.getCanonicalFile (io/file workspace-root))
         workspace-lock (acquire-workspace-lock! workspace)
         started-server (atom nil)]
     (try
       (let [project (editor/start-project! workspace overrides)
             authentication-required? (authentication-required? project)
             token (if authentication-required?
                     (str (UUID/randomUUID) (UUID/randomUUID))
                     "")
             shutdown-requested (promise)
             _ (if authentication-required?
                 (System/setProperty "aguafria.editor.token" token)
                 (System/clearProperty "aguafria.editor.token"))
             server (nrepl/start-server
                     :bind "127.0.0.1"
                     :port 0
                     :handler (nrepl/default-handler #'aguafria-nrepl/wrap-editor))
             _ (reset! started-server server)
             port-file (io/file workspace ".nrepl-port")
             runtime-file (io/file workspace ".aguafria" "runtime.edn")
             token-file (io/file workspace ".aguafria" "editor-token")
             port-content (str (:port server))
             handshake
             {:protocol-version 1
              :project-id (:project-id project)
              :profile (get-in project [:configuration :profile])
              :workspace-root (.getAbsolutePath workspace)
              :project-root (get-in project [:configuration :project-root])
              :jvm-pid (.pid (java.lang.ProcessHandle/current))
              :runtime-id (:runtime-id project)
              :host-generation 0
              :port (:port server)
              :bind "127.0.0.1"
              :started-at-ms (System/currentTimeMillis)
              :authentication-required? authentication-required?
              :token-fingerprint (sha256 token)}
             runtime-content (with-out-str (pprint/pprint handshake))]
         (when authentication-required?
           (atomic-write! token-file token)
           (protect-token! token-file))
         (atomic-write! runtime-file runtime-content)
         ;; Publish the conventional discovery file last. Its presence means the
         ;; server and Aguafria middleware are both ready.
         (atomic-write! port-file port-content)
         (let [cleaned? (atom false)
               cleanup!
               (fn []
                 (when (compare-and-set! cleaned? false true)
                   (aguafria-nrepl/unregister-shutdown-handler!
                    (:runtime-id handshake))
                   (delete-owned-file! port-file port-content)
                   (delete-owned-file! runtime-file runtime-content)
                   (when authentication-required?
                     (delete-owned-file! token-file token))
                   (when (= token (System/getProperty "aguafria.editor.token"))
                     (System/clearProperty "aguafria.editor.token"))
                   (release-workspace-lock! workspace-lock)))]
           (.addShutdownHook
            (Runtime/getRuntime)
            (Thread. ^Runnable
                     (reify Runnable
                       (run [_] (cleanup!)))
                     "aguafria-editor-discovery-cleanup"))
           (aguafria-nrepl/register-shutdown-handler!
            (:runtime-id handshake)
            (fn []
              (nrepl/stop-server server)
              (cleanup!)
              (deliver shutdown-requested true)))
           {:server server
            :project project
            :handshake handshake
            :token token
            :workspace-lock workspace-lock
            :shutdown-requested shutdown-requested
            :files {:port (.getAbsolutePath port-file)
                    :runtime (.getAbsolutePath runtime-file)
                    :token (when authentication-required?
                             (.getAbsolutePath token-file))
                    :lock (.getAbsolutePath
                           ^java.io.File (:file workspace-lock))}
            :cleanup! cleanup!}))
       (catch Throwable error
         (when-let [server @started-server]
           (nrepl/stop-server server))
         (release-workspace-lock! workspace-lock)
         (throw error))))))

(defn stop!
  "Stop an editor server returned by `start!` and remove only its own files."
  [{:keys [server cleanup! shutdown-requested]}]
  (when server (nrepl/stop-server server))
  (when cleanup! (cleanup!))
  (when shutdown-requested (deliver shutdown-requested true))
  nil)

(defn -main
  [& [workspace-root]]
  (let [workspace-root (or workspace-root (System/getProperty "user.dir"))
        {:keys [handshake shutdown-requested]} (start! workspace-root)]
    (binding [*out* *err*]
      (println (str "Aguafria editor runtime ready on nrepl://127.0.0.1:"
                    (:port handshake)))
      (flush))
    @shutdown-requested))
