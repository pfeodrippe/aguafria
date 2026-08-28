(ns aguafria.zig.toolchain
  "Locate, verify, and atomically materialize Aguafria's embedded Zig toolchain.

  There is intentionally no PATH, environment, or configured-compiler fallback:
  the platform artifact is the compiler identity."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedInputStream File]
           [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest
            HttpResponse$BodyHandlers]
           [java.nio.channels FileChannel FileLock]
           [java.nio.file AtomicMoveNotSupportedException CopyOption Files LinkOption
            OpenOption Path StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute PosixFilePermission]
           [java.security DigestInputStream MessageDigest]
           [java.util HexFormat UUID]
           [org.apache.commons.compress.archivers.tar TarArchiveEntry TarArchiveInputStream]
           [org.apache.commons.compress.compressors.xz XZCompressorInputStream]))

(def ^:private manifest-resource "aguafria/toolchain/manifest.edn")
(def ^:private releases-resource "aguafria/toolchain/releases.edn")
(def ^:private lock-options
  (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
(def ^:private empty-file-attributes (make-array FileAttribute 0))
(def ^:private no-link-options (make-array LinkOption 0))
(defonce ^:private materialized (atom nil))
(defonce ^:private materialization-lock (Object.))
(def ^:private http-client
  (-> (HttpClient/newBuilder)
      (.followRedirects HttpClient$Redirect/ALWAYS)
      .build))

(defn- normalize-os
  [value]
  (let [value (str/lower-case (str value))]
    (cond
      (str/includes? value "mac") :macos
      (str/includes? value "linux") :linux
      (str/includes? value "windows") :windows
      :else (keyword (str/replace value #"[^a-z0-9]+" "-")))))

(defn- normalize-arch
  [value]
  (case (str/lower-case (str value))
    ("aarch64" "arm64") :aarch64
    ("x86_64" "amd64") :x86-64
    ("x86" "i386" "i686") :x86
    (keyword (str/lower-case (str value)))))

(defn host-platform
  "Return Aguafria's normalized host OS and architecture."
  []
  {:os (normalize-os (System/getProperty "os.name"))
   :arch (normalize-arch (System/getProperty "os.arch"))})

(defn- source-checkout-manifest
  []
  (let [source (io/resource "aguafria/zig/toolchain.clj")
        releases-url (io/resource releases-resource)]
    (when (and source (= "file" (.getProtocol source)) releases-url)
      (let [releases (edn/read-string (slurp releases-url))
            host (host-platform)
            platform (some #(when (= host (select-keys % [:os :arch])) %)
                           (:platforms releases))]
        (when platform
          (-> platform
              (dissoc :id :binary-format)
              (assoc :schema-version 1
                     :artifact "aguafria/source-checkout"
                     :version "development"
                     :zig-version (:zig-version releases)
                     :archive-resource "aguafria/toolchain/zig.tar.xz"
                     :archive-minisig-resource
                     "aguafria/toolchain/zig.tar.xz.minisig"
                     :source-checkout? true)))))))

(defn manifest
  "Return the embedded platform manifest without extracting Zig."
  []
  (if-let [resource (io/resource manifest-resource)]
    (with-open [reader (java.io.PushbackReader. (io/reader resource))]
      (let [value (edn/read reader)]
        (when-not (map? value)
          (throw (ex-info "Aguafria's embedded Zig manifest is malformed"
                          {:aguafria/phase :embedded-zig-manifest
                           :resource manifest-resource
                           :value value})))
        value))
    (or
     (source-checkout-manifest)
     (throw
      (ex-info
       "This classpath has no embedded Zig toolchain. Depend on the Aguafria artifact matching this host platform."
       {:aguafria/phase :embedded-zig-missing
        :host (host-platform)
        :resource manifest-resource
        :hint "Use io.github.pfeodrippe/aguafria-<os>-<architecture>; Aguafria never uses Zig from PATH."})))))

(defn- verify-platform!
  [{:keys [os arch] :as embedded}]
  (let [host (host-platform)]
    (when-not (= host {:os os :arch arch})
      (throw
       (ex-info
        "The embedded Zig toolchain does not match this JVM host"
        {:aguafria/phase :embedded-zig-platform
         :host host
         :embedded (select-keys embedded [:artifact :version :zig-version :os :arch])
         :hint "Select the Aguafria Maven Central artifact for this OS and CPU architecture."})))
    embedded))

(defn- cache-root
  []
  (let [home (System/getProperty "user.home")
        os (:os (host-platform))]
    (io/file
     (case os
       :macos (io/file home "Library" "Caches")
       :windows (or (System/getenv "LOCALAPPDATA")
                    (str (io/file home "AppData" "Local")))
       (or (System/getenv "XDG_CACHE_HOME")
           (str (io/file home ".cache"))))
     "aguafria" "toolchains")))

(defn- sha256-file
  [^File file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input (DigestInputStream. (io/input-stream file) digest)]
      (.transferTo input (java.io.OutputStream/nullOutputStream)))
    (.formatHex (HexFormat/of) (.digest digest))))

(defn- move-atomically!
  [^Path source ^Path target]
  (try
    (Files/move source target
                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
    (catch AtomicMoveNotSupportedException _
      (Files/move source target (make-array CopyOption 0)))))

(defn- safe-target
  [^Path root name]
  (let [target (.normalize (.resolve root name))]
    (when-not (.startsWith target root)
      (throw (ex-info "Embedded Zig archive entry escapes its extraction root"
                      {:aguafria/phase :embedded-zig-extract
                       :entry name
                       :root (str root)})))
    target))

(defn- executable-entry?
  [^TarArchiveEntry entry]
  (not (zero? (bit-and (.getMode entry) 8r111))))

(defn- set-executable!
  [^Path path]
  (try
    (let [permissions (Files/getPosixFilePermissions path no-link-options)]
      (.add permissions PosixFilePermission/OWNER_EXECUTE)
      (Files/setPosixFilePermissions path permissions))
    (catch UnsupportedOperationException _
      (.setExecutable (.toFile path) true true))))

(defn- extract-archive!
  [^File archive ^Path root]
  (Files/createDirectories root empty-file-attributes)
  (with-open [input (-> archive io/input-stream BufferedInputStream.
                        XZCompressorInputStream.)
              tar (TarArchiveInputStream. input)]
    (loop [pending-hard-links []]
      (if-let [^TarArchiveEntry entry (.getNextEntry tar)]
        (let [target (safe-target root (.getName entry))
              pending-hard-links
              (cond
                (.isDirectory entry)
                (do
                  (Files/createDirectories target empty-file-attributes)
                  pending-hard-links)

                (.isSymbolicLink entry)
                (let [link (Path/of (.getLinkName entry) (make-array String 0))
                      parent (.getParent target)
                      resolved-link (.normalize (.resolve parent link))]
                  (when (or (.isAbsolute link)
                            (not (.startsWith resolved-link root)))
                    (throw (ex-info "Embedded Zig archive contains an unsafe symbolic link"
                                    {:aguafria/phase :embedded-zig-extract
                                     :entry (.getName entry)
                                     :target (.getLinkName entry)})))
                  (Files/createDirectories parent empty-file-attributes)
                  (Files/createSymbolicLink target link empty-file-attributes)
                  pending-hard-links)

                (.isLink entry)
                (conj pending-hard-links
                      [target (safe-target root (.getLinkName entry))])

                :else
                (do
                  (Files/createDirectories (.getParent target) empty-file-attributes)
                  (Files/copy tar target
                              (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
                  (when (executable-entry? entry)
                    (set-executable! target))
                  pending-hard-links))]
          (recur pending-hard-links))
        (doseq [[^Path target ^Path existing] pending-hard-links]
          (Files/createDirectories (.getParent target) empty-file-attributes)
          (Files/createLink target existing))))))

(defn- copy-source-checkout-archive!
  [{:keys [archive-url]} ^File target]
  (let [request (-> (HttpRequest/newBuilder (URI/create archive-url)) .GET .build)
        response (.send http-client request
                        (HttpResponse$BodyHandlers/ofInputStream))]
    (when-not (= 200 (.statusCode response))
      (throw (ex-info "Unable to download Aguafria's pinned development Zig archive"
                      {:aguafria/phase :embedded-zig-download
                       :url archive-url
                       :status (.statusCode response)})))
    (with-open [input ^java.io.InputStream (.body response)
                output (io/output-stream target)]
      (.transferTo input output))))

(defn- copy-verified-archive!
  [{:keys [archive-resource archive-resources archive-sha256 archive-size
           source-checkout?]
    :as embedded} ^File target]
  (let [resource-names (or (seq archive-resources)
                           (when archive-resource [archive-resource]))
        resources (mapv io/resource resource-names)]
  (if (and (seq resources) (every? some? resources))
    (do
      (.mkdirs (.getParentFile target))
      (with-open [output (io/output-stream target)]
        (doseq [resource resources]
          (with-open [input (io/input-stream resource)]
            (.transferTo input output))))
      target)
    (if source-checkout?
      (do
        (.mkdirs (.getParentFile target))
        (copy-source-checkout-archive! embedded target)
        target)
      (throw (ex-info "The embedded Zig archive resource is missing"
                      {:aguafria/phase :embedded-zig-missing
                       :resources resource-names}))))
  (let [actual-size (.length target)
        actual-sha256 (sha256-file target)]
    (when-not (and (= (long archive-size) actual-size)
                   (= archive-sha256 actual-sha256))
      (throw
       (ex-info "The embedded Zig archive failed integrity verification"
                {:aguafria/phase :embedded-zig-integrity
                 :resources resource-names
                 :expected {:size archive-size :sha256 archive-sha256}
                 :actual {:size actual-size :sha256 actual-sha256}}))))
  target))

(defn- run-version
  [^File executable]
  (let [process (.start (ProcessBuilder. ^java.util.List
                                         [(.getAbsolutePath executable) "version"]))
        stdout (atom "")
        stderr (atom "")
        stdout-thread (doto (Thread. ^Runnable
                                     #(reset! stdout
                                              (slurp (.getInputStream process)))
                                     "aguafria-toolchain-version-stdout")
                        (.setDaemon true)
                        (.start))
        stderr-thread (doto (Thread. ^Runnable
                                     #(reset! stderr
                                              (slurp (.getErrorStream process)))
                                     "aguafria-toolchain-version-stderr")
                        (.setDaemon true)
                        (.start))
        exit (.waitFor process)
        _ (.join stdout-thread)
        _ (.join stderr-thread)]
    {:exit exit :out (str/trim @stdout) :err @stderr}))

(defn- valid-installation?
  [^File executable zig-version]
  (and (.isFile executable)
       (.canExecute executable)
       (let [{:keys [exit out]} (run-version executable)]
         (and (zero? exit) (= zig-version out)))))

(defn- materialize!
  [{:keys [artifact version zig-version os arch archive-sha256 root-directory
           executable] :as embedded}]
  (let [directory (io/file (cache-root) zig-version
                           (str (name os) "-" (name arch)) archive-sha256)
        installation (io/file directory "toolchain")
        zig-file (io/file installation root-directory executable)
        lock-file (io/file directory ".materialize.lock")]
    (.mkdirs directory)
    (with-open [channel (FileChannel/open (.toPath lock-file) lock-options)
                ^FileLock _ (.lock channel)]
      (if (valid-installation? zig-file zig-version)
        (.getCanonicalPath zig-file)
        (let [nonce (str (UUID/randomUUID))
              archive (io/file directory (str ".archive-" nonce ".tar.xz"))
              unpack (io/file directory (str ".unpack-" nonce))]
          (try
            (copy-verified-archive! embedded archive)
            (extract-archive! archive (.toPath unpack))
            (let [candidate (io/file unpack root-directory executable)]
              (when-not (valid-installation? candidate zig-version)
                (throw
                 (ex-info "The extracted embedded Zig compiler is not executable or has the wrong version"
                          {:aguafria/phase :embedded-zig-validation
                           :artifact artifact
                           :version version
                           :expected-zig-version zig-version
                           :path (.getAbsolutePath candidate)})))
              (when (Files/exists (.toPath installation) no-link-options)
                (throw
                 (ex-info "An invalid embedded Zig cache entry already exists"
                          {:aguafria/phase :embedded-zig-cache
                           :path (.getAbsolutePath installation)
                           :hint "Remove this one checksum-addressed Aguafria toolchain directory and retry."})))
              (move-atomically! (.toPath unpack) (.toPath installation))
              (.getCanonicalPath zig-file))
            (finally
              (Files/deleteIfExists (.toPath archive)))))))))

(defn executable
  "Return the absolute executable path of the verified embedded Zig compiler.

  The first call extracts the immutable archive into Aguafria's user cache.
  Subsequent JVMs validate and reuse that checksum-addressed installation."
  []
  (or @materialized
      (locking materialization-lock
        (or @materialized
            (let [path (materialize! (verify-platform! (manifest)))]
              (reset! materialized path)
              path)))))

(defn information
  "Return inspectable embedded-toolchain identity and materialization state."
  []
  (assoc (verify-platform! (manifest))
         :host (host-platform)
         :materialized? (boolean @materialized)
         :executable @materialized))
