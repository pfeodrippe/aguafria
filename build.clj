(ns build
  "Build and publish self-contained Aguafria platform JARs."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b])
  (:import [java.security DigestInputStream MessageDigest]
           [java.util HexFormat]
           [java.util.zip ZipFile]))

(def ^:private group-id "io.github.pfeodrippe")
(def ^:private target-dir "target/release")
(def ^:private releases
  (edn/read-string (slurp "resources/aguafria/toolchain/releases.edn")))
(def ^:private zig-version (:zig-version releases))
(def ^:private basis (delay (b/create-basis {:project "deps.edn"})))

(def ^:private pom-data
  [[:description
    "Pure Zig programs authored from Clojure, with native-speed standalone builds and REPL hot reload."]
   [:url "https://github.com/pfeodrippe/aguafria"]
   [:licenses
    [:license
     [:name "MIT License"]
     [:url "https://opensource.org/license/mit"]
     [:distribution "repo"]]]
   [:developers
    [:developer
     [:id "pfeodrippe"]
     [:name "Paulo Feodrippe"]
     [:email "pfeodrippe@gmail.com"]
     [:url "https://github.com/pfeodrippe"]]]])

(def ^:private scm
  {:connection "scm:git:https://github.com/pfeodrippe/aguafria.git"
   :developerConnection "scm:git:ssh://git@github.com/pfeodrippe/aguafria.git"
   :url "https://github.com/pfeodrippe/aguafria"})

(defn- current-version
  []
  (str/trim (slurp "VERSION")))

(defn- parse-version
  [value]
  (or (when-let [[_ major minor patch]
                 (re-matches #"(\d+)\.(\d+)\.(\d+)" value)]
        (mapv parse-long [major minor patch]))
      (throw (ex-info "VERSION must be major.minor.patch" {:version value}))))

(defn- next-version
  []
  (let [[major minor patch] (parse-version (current-version))]
    (str major "." minor "." (inc patch))))

(defn next-patch
  "Print the next release version."
  [_]
  (println (next-version)))

(defn- digest
  [algorithm input]
  (let [message-digest (MessageDigest/getInstance algorithm)]
    (with-open [stream (DigestInputStream. (io/input-stream input) message-digest)]
      (.transferTo stream (java.io.OutputStream/nullOutputStream)))
    (.formatHex (HexFormat/of) (.digest message-digest))))

(defn- require-success!
  [description result]
  (when-not (zero? (:exit result))
    (throw (ex-info description result)))
  result)

(defn- download!
  [{:keys [artifact-id archive-url archive-size archive-sha256]}]
  (let [directory (str target-dir "/downloads")
        archive (str directory "/" artifact-id "-zig-" zig-version ".tar.xz")
        signature (str archive ".minisig")
        valid? (and (.isFile (io/file archive))
                    (= archive-size (.length (io/file archive)))
                    (= archive-sha256 (digest "SHA-256" archive)))]
    (when-not valid?
      (b/delete {:path archive})
      (io/make-parents archive)
      (require-success!
       "Unable to download the pinned Zig archive"
       (b/process {:command-args ["curl" "--fail" "--location" "--silent"
                                  "--show-error" "--output" archive archive-url]
                   :out :capture :err :capture})))
    (when-not (and (= archive-size (.length (io/file archive)))
                   (= archive-sha256 (digest "SHA-256" archive)))
      (throw (ex-info "Downloaded Zig archive failed integrity verification"
                      {:archive archive
                       :expected-size archive-size
                       :expected-sha256 archive-sha256})))
    (when-not (.isFile (io/file signature))
      (require-success!
       "Unable to download the Zig archive signature"
       (b/process {:command-args ["curl" "--fail" "--location" "--silent"
                                  "--show-error" "--output" signature
                                  (str archive-url ".minisig")]
                   :out :capture :err :capture})))
    {:archive archive :signature signature}))

(defn- select-platforms
  [{:keys [platform]}]
  (if platform
    (let [id (keyword platform)]
      [(or (some #(when (= id (:id %)) %) (:platforms releases))
           (throw (ex-info "Unknown release platform"
                           {:platform platform
                            :available (mapv :id (:platforms releases))})))])
    (:platforms releases)))

(defn- manifest
  [{:keys [id artifact-id os arch archive-url archive-size archive-sha256
           root-directory executable]} release-version]
  {:schema-version 1
   :artifact (str group-id "/" artifact-id)
   :version release-version
   :zig-version zig-version
   :os os
   :arch arch
   :archive-resource "aguafria/toolchain/zig.tar.xz"
   :archive-minisig-resource "aguafria/toolchain/zig.tar.xz.minisig"
   :archive-url archive-url
   :archive-size archive-size
   :archive-sha256 archive-sha256
   :root-directory root-directory
   :executable executable
   :platform id})

(defn- package-platform!
  [platform release-version]
  (let [{:keys [artifact-id]} platform
        lib (symbol group-id artifact-id)
        root (str target-dir "/work/" artifact-id)
        classes (str root "/classes")
        sources (str root "/sources")
        javadoc (str root "/javadoc")
        artifacts (str target-dir "/artifacts")
        prefix (str artifacts "/" artifact-id "-" release-version)
        jar (str prefix ".jar")
        source-jar (str prefix "-sources.jar")
        javadoc-jar (str prefix "-javadoc.jar")
        pom (str prefix ".pom")
        {:keys [archive signature]} (download! platform)]
    (b/delete {:path root})
    (b/copy-dir {:src-dirs ["src" "resources"] :target-dir classes})
    (doseq [file ["LICENSE" "THIRD_PARTY_NOTICES.md"]]
      (b/copy-file {:src file :target (str classes "/" file)}))
    (b/copy-file {:src archive
                  :target (str classes "/aguafria/toolchain/zig.tar.xz")})
    (b/copy-file {:src signature
                  :target (str classes "/aguafria/toolchain/zig.tar.xz.minisig")})
    (b/write-file {:path (str classes "/aguafria/toolchain/manifest.edn")
                   :string (str (pr-str (manifest platform release-version)) "\n")})
    (b/write-pom {:class-dir classes
                  :lib lib
                  :version release-version
                  :basis @basis
                  :src-pom :none
                  :src-dirs ["src"]
                  :resource-dirs ["resources"]
                  :repos {}
                  :pom-data pom-data
                  :scm scm})
    (b/jar {:class-dir classes :jar-file jar})
    (b/copy-file {:src (b/pom-path {:class-dir classes :lib lib}) :target pom})

    (b/copy-dir {:src-dirs ["src"] :target-dir sources})
    (doseq [file ["README.md" "LICENSE" "THIRD_PARTY_NOTICES.md"]]
      (b/copy-file {:src file :target (str sources "/" file)}))
    (b/jar {:class-dir sources :jar-file source-jar})

    (doseq [file ["README.md" "LICENSE"]]
      (b/copy-file {:src file :target (str javadoc "/" file)}))
    (b/jar {:class-dir javadoc :jar-file javadoc-jar})
    {:platform (:id platform)
     :artifact (str lib)
     :artifact-id artifact-id
     :version release-version
     :jar jar
     :sources source-jar
     :javadoc javadoc-jar
     :pom pom
     :archive-sha256 (:archive-sha256 platform)
     :archive-size (:archive-size platform)}))

(defn package
  "Build one self-contained Maven artifact per selected OS."
  [{:keys [version] :as options}]
  (let [release-version (or (some-> version str) (current-version))]
    (parse-version release-version)
    (b/delete {:path (str target-dir "/work")})
    (b/delete {:path (str target-dir "/artifacts")})
    (let [artifacts (mapv #(package-platform! % release-version)
                          (select-platforms options))]
      (doseq [{:keys [artifact jar]} artifacts]
        (println "packaged" artifact release-version
                 (str (.length (io/file jar)) " bytes")))
      {:version release-version :artifacts artifacts})))

(defn package-next-patch
  "Package the next patch release."
  [options]
  (package (assoc options :version (next-version))))

(defn- verify-archive!
  [{:keys [jar archive-size archive-sha256 artifact]}]
  (with-open [zip (ZipFile. ^String jar)]
    (let [entry (.getEntry zip "aguafria/toolchain/zig.tar.xz")]
      (when-not entry
        (throw (ex-info "Platform JAR is missing embedded Zig"
                        {:artifact artifact})))
      (when-not (= archive-size (.getSize entry))
        (throw (ex-info "Embedded Zig has the wrong size"
                        {:artifact artifact})))
      (with-open [input (.getInputStream zip entry)]
        (when-not (= archive-sha256 (digest "SHA-256" input))
          (throw (ex-info "Embedded Zig has the wrong checksum"
                          {:artifact artifact})))))))

(defn- host-platform
  []
  {:os (if (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
         :macos :linux)
   :arch (if (#{"aarch64" "arm64"} (System/getProperty "os.arch"))
           :aarch64 :x86-64)})

(defn- verify-host!
  [artifacts]
  (when-let [platform (some #(when (= (host-platform)
                                       (select-keys % [:os :arch])) %)
                            (:platforms releases))]
    (when-let [artifact (some #(when (= (:id platform) (:platform %)) %)
                              artifacts)]
      (let [home (str target-dir "/verify-home")
            expression
            (str "(require '[aguafria.zig :as az])"
                 "(eval '(az/defn release-smoke :- :i64 "
                 "[a :- :i64 b :- :i64 c :- :i64] (+ (* a b) c)))"
                 "(assert (= 47 ((resolve 'user/release-smoke) 6 7 5)))"
                 "(assert (= \"" zig-version
                 "\" (:zig-version (az/toolchain-information))))"
                 "(println :embedded-zig-smoke-ok)"
                 "(shutdown-agents)(System/exit 0)")
            command (b/java-command
                     {:cp [(:jar artifact)]
                      :basis @basis
                      :java-opts ["--enable-native-access=ALL-UNNAMED"
                                  (str "-Duser.home=" home)
                                  (str "-Daguafria.cache-dir=" home "/compile-cache")]
                      :main 'clojure.main
                      :main-args ["-e" expression]})]
        (b/delete {:path home})
        (require-success! "Published-package host smoke test failed"
                          (b/process (assoc command :out :capture :err :capture)))
        (b/delete {:path home})
        (println "verified embedded Zig" zig-version "with" (:artifact artifact))))))

(defn verify
  "Package, verify each embedded archive, and execute the host package."
  [options]
  (let [{:keys [artifacts] :as release} (package options)]
    (run! verify-archive! artifacts)
    (verify-host! artifacts)
    release))

(defn verify-next-patch
  "Verify the next patch release."
  [options]
  (verify (assoc options :version (next-version))))

(defn- sign!
  [file]
  (require-success!
   "GPG could not sign a Maven Central artifact"
   (b/process {:command-args ["gpg" "--batch" "--yes" "--armor"
                              "--detach-sign" file]
               :out :capture :err :capture})))

(defn- stage-central!
  [{:keys [version artifacts]}]
  (let [staging (str target-dir "/central-staging")
        bundle (str target-dir "/aguafria-" version "-central.zip")]
    (b/delete {:path staging})
    (doseq [{:keys [artifact-id jar sources javadoc pom]} artifacts
            :let [directory (str staging "/io/github/pfeodrippe/"
                                 artifact-id "/" version)
                  files [[jar (str artifact-id "-" version ".jar")]
                         [sources (str artifact-id "-" version "-sources.jar")]
                         [javadoc (str artifact-id "-" version "-javadoc.jar")]
                         [pom (str artifact-id "-" version ".pom")]]]
            [source filename] files]
      (let [target (str directory "/" filename)]
        (b/copy-file {:src source :target target})
        (sign! target)
        (doseq [[algorithm suffix] [["MD5" ".md5"] ["SHA-1" ".sha1"]]]
          (b/write-file {:path (str target suffix)
                         :string (digest algorithm target)}))))
    (b/zip {:src-dirs [staging] :zip-file bundle})
    (println "created Maven Central bundle" bundle)
    bundle))

(defn central-bundle
  "Verify and create the signed Maven Central bundle without uploading it."
  [options]
  (let [release (verify options)]
    (assoc release :bundle (stage-central! release))))

(defn central-bundle-next-patch
  "Create the signed bundle for the next patch release."
  [options]
  (central-bundle (assoc options :version (next-version))))

(defn- module-pom
  [pom]
  (let [xml (slurp pom)]
    (when-not (str/includes? xml "</build>")
      (throw (ex-info "tools.build produced an invalid POM" {:pom pom})))
    (str/replace xml "</build>"
                 (str (slurp "build-resources/central/plugins.xml")
                      "\n  </build>"))))

(defn- reactor-pom
  [artifacts release-version]
  (-> (slurp "build-resources/central/reactor-pom.xml")
      (str/replace "{{version}}" release-version)
      (str/replace "{{modules}}"
                   (str/join "\n"
                             (map #(str "    <module>" (:artifact-id %)
                                        "</module>")
                                  artifacts)))))

(defn prepare-central-publish
  "Verify artifacts and prepare a standard Maven reactor for Central publishing."
  [{:keys [version] :as options}]
  (let [release-version (or (some-> version str) (next-version))
        {:keys [artifacts]} (verify (assoc options :version release-version))
        root (str target-dir "/maven-publish")]
    (b/delete {:path root})
    (b/write-file {:path (str root "/pom.xml")
                   :string (reactor-pom artifacts release-version)})
    (doseq [{:keys [artifact-id jar sources javadoc pom]} artifacts
            :let [module (str root "/" artifact-id)
                  prepared (str module "/prepared")]]
      (b/write-file {:path (str module "/pom.xml") :string (module-pom pom)})
      (b/copy-file {:src jar :target (str prepared "/main.jar")})
      (b/copy-file {:src sources :target (str prepared "/sources.jar")})
      (b/copy-file {:src javadoc :target (str prepared "/javadoc.jar")}))
    (println (str root "/pom.xml"))
    {:version release-version :pom (str root "/pom.xml")}))

(defn advance-version
  "Advance VERSION after Maven Central has confirmed publication."
  [{:keys [version]}]
  (let [release-version (str version)]
    (parse-version release-version)
    (when-not (= release-version (next-version))
      (throw (ex-info "Refusing to advance VERSION out of sequence"
                      {:current (current-version) :requested release-version})))
    (b/write-file {:path "VERSION" :string (str release-version "\n")})
    (println "advanced VERSION to" release-version)))

(defn clean
  "Remove regenerable release output."
  [_]
  (b/delete {:path target-dir}))
