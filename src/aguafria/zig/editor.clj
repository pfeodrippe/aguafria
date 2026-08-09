(ns aguafria.zig.editor
  "Editor-facing pure Zig hot-reload API.

  This namespace owns project/document identity and translates unsaved Zig
  buffers into the same declaration descriptors used by Aguafria's Clojure
  API. It deliberately has no nREPL dependency; transports adapt these plain
  data functions at the boundary."
  (:refer-clojure :exclude [await])
  (:require [aguafria.zig.convert :as convert]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as zig-value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardOpenOption]
           [java.security MessageDigest]
           [java.util HexFormat UUID]))

(def ^:private protocol-version 1)
(defonce ^:private runtime-id (str (UUID/randomUUID)))
(defonce ^:private projects (atom {}))
(defonce ^:private tickets (atom {}))
(defonce ^:private ticket-order (atom []))
(def ^:private ticket-history-limit 200)
(def ^:private default-max-source-bytes (* 16 1024 1024))
(def ^:private external-publication-lock (Object.))

(def ^:private runtime-configuration-keys
  [:zig :cache-dir :optimize :development-debug-info :development-panic
   :cpu :zig-args :async? :reloadable? :compile-debounce-ms
   :converted-compile-debounce-ms :build-history-limit])

(defn- sha256
  [value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes (str value) StandardCharsets/UTF_8))
    (.formatHex (HexFormat/of) (.digest digest))))

(defn- canonical-file
  [base path]
  (.getCanonicalFile
   (let [file (io/file (str path))]
     (if (.isAbsolute file) file (io/file base (str path))))))

(defn- read-edn-file
  [file]
  (when (.isFile ^java.io.File file)
    (try
      (edn/read-string (slurp file))
      (catch Throwable error
        (throw (ex-info "Unable to read Aguafria project configuration"
                        {:aguafria/phase :zig-editor-config
                         :path (.getAbsolutePath ^java.io.File file)}
                        error))))))

(defn- normalized-profile
  [profile]
  (keyword (or (some-> profile name) "development")))

(defn- project-slug
  [root profile]
  (str "p" (subs (sha256 [(.getAbsolutePath ^java.io.File root) profile]) 0 16)))

(defn configuration
  "Resolve a workspace's `aguafria.edn` and explicit editor overrides.

  Paths are relative to the configuration file's directory. `:project-root`
  defaults to the workspace root. No declaration-level configuration is used."
  ([workspace-root] (configuration workspace-root {}))
  ([workspace-root overrides]
   (let [workspace-root (.getCanonicalFile (io/file workspace-root))
         config-file (io/file workspace-root "aguafria.edn")
         file-config (or (read-edn-file config-file) {})
         config (merge file-config overrides)
         project-root (canonical-file workspace-root
                                      (or (:project-root config) "."))
         profile (normalized-profile (:profile config))
         project-id (or (:project-id config)
                        (project-slug project-root profile))
         namespace-prefix
         (symbol (str (or (:namespace-prefix config)
                          (str "aguafria.editor." project-id))))]
     (when-not (.isDirectory project-root)
       (throw (ex-info "Aguafria Zig project root is not a directory"
                       {:aguafria/phase :zig-editor-config
                        :workspace-root (.getAbsolutePath workspace-root)
                        :project-root (.getAbsolutePath project-root)})))
     (merge config
            {:protocol-version protocol-version
             :workspace-root (.getAbsolutePath workspace-root)
             :config-path (when (.isFile config-file)
                            (.getAbsolutePath config-file))
             :project-root (.getAbsolutePath project-root)
             :profile profile
             :project-id (str project-id)
             :namespace-prefix namespace-prefix
             :max-source-bytes
             (long (or (:max-source-bytes config)
                       default-max-source-bytes))}))))

(defn start-project!
  "Register one editor-owned Zig project in this JVM.

  This establishes identity and compiler configuration. Project graph
  bootstrap and native-main startup may follow without changing the id."
  ([workspace-root] (start-project! workspace-root {}))
  ([workspace-root overrides]
   (let [config (configuration workspace-root overrides)
         project-id (:project-id config)
         now (System/currentTimeMillis)
         runtime-options
         (cond-> (select-keys config runtime-configuration-keys)
           (:zig-target config) (assoc :target (:zig-target config)))
         _ (runtime/configure! runtime-options)
         project
         (swap! projects update project-id
                (fn [existing]
                  (merge existing
                         {:project-id project-id
                          :runtime-id runtime-id
                          :status :ready
                          :started-at-ms (or (:started-at-ms existing) now)
                          :updated-at-ms now
                          :configuration config
                          :documents (or (:documents existing) {})})))]
     (select-keys (get project project-id)
                  [:project-id :runtime-id :status :started-at-ms
                   :updated-at-ms :configuration]))))

(defn stop-project!
  "Stop an editor-owned external program and forget project bookkeeping.

  Only the exact `Process` launched for this project is stopped. JVM-owned
  native hosts and unrelated operating-system processes are never searched for
  or killed."
  [project-id]
  (let [project (get @projects (str project-id))]
    (when-let [^Process process (get-in project [:internal :external-process])]
      (when (.isAlive process)
        (.destroy process)))
    (swap! projects dissoc (str project-id))
    (when project
      {:project-id (str project-id)
       :runtime-id runtime-id
       :status :stopped})))

(declare project! module-for-uri)

(defn- configured-file
  [configuration path]
  (canonical-file (:workspace-root configuration) path))

(defn- run-development-build!
  [configuration output-root build]
  (when build
    (let [build (if (map? build) build {:command build})
          command (mapv str (:command build))
          _ (when-not (seq command)
              (throw
               (ex-info "Development build has no command"
                        {:aguafria/phase :zig-editor-development-build})))
          working-directory-file
          (if-let [directory (:working-directory build)]
            (configured-file configuration directory)
            (io/file output-root))
          started (System/nanoTime)
          process (.start
                   (doto (ProcessBuilder. ^java.util.List command)
                     (.directory working-directory-file)
                     (.inheritIO)))
          exit (.waitFor process)
          report {:command command
                  :working-directory
                  (.getAbsolutePath ^java.io.File working-directory-file)
                  :exit exit
                  :elapsed-ms (/ (- (System/nanoTime) started) 1e6)}]
      (when-not (zero? exit)
        (throw
         (ex-info "Generated Zig development build failed"
                  (assoc report
                         :aguafria/phase :zig-editor-development-build))))
      report)))

(defn- external-program-command
  [configuration]
  (let [external (:external-program configuration)
        executable (if (map? external) (:executable external) external)
        arguments (if (map? external) (vec (:arguments external)) [])
        open-file-limit (when (map? external) (:open-file-limit external))]
    (when executable
      (let [command
            (into [(.getAbsolutePath
                    ^java.io.File (configured-file configuration executable))]
                  (map str)
                  arguments)]
        (if open-file-limit
          (into ["/bin/sh" "-c"
                 "ulimit -n \"$1\" || exit $?; shift; exec \"$@\""
                 "aguafria-external-program" (str (long open-file-limit))]
                command)
          command)))))

(defn- external-program-view
  [^Process process command working-directory]
  {:kind :external
   :status (if (.isAlive process) :running :exited)
   :pid (.pid process)
   :command command
   :working-directory working-directory
   :exit-code (when-not (.isAlive process) (.exitValue process))})

(defn- start-external-program!
  [project-id project]
  (let [{:keys [configuration]} project
        external (:external-program configuration)
        command (external-program-command configuration)
        _ (when-not (seq command)
            (throw (ex-info "External development program has no executable"
                            {:aguafria/phase :zig-editor-program
                             :project-id project-id
                             :external-program external})))
        configured-executable
        (if (map? external) (:executable external) external)
        executable (configured-file configuration configured-executable)
        _ (when-not (.isFile executable)
            (throw (ex-info "External development-program executable does not exist"
                            {:aguafria/phase :zig-editor-program
                             :project-id project-id
                             :executable (.getAbsolutePath executable)})))
        working-directory-file
        (configured-file configuration
                         (or (when (map? external)
                               (:working-directory external))
                             "."))
        working-directory (.getAbsolutePath working-directory-file)
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.directory working-directory-file)
                  (.inheritIO))
        environment (.environment builder)
        ;; ProcessBuilder changes the real cwd but leaves an inherited `PWD`
        ;; untouched. Native programs are allowed to trust `PWD`; an
        ;; inconsistent value has caused real debug builds (including Ghostty)
        ;; to fail before their UI starts.
        _ (.put environment "PWD" working-directory)
        configured-environment (if (map? external) (:environment external) {})
        _ (doseq [[name value] configured-environment]
            (.put environment (str name) (str value)))
        configured-environment-paths
        (if (map? external) (:environment-paths external) {})
        _ (doseq [[name path] configured-environment-paths]
            (.put environment
                  (str name)
                  (.getAbsolutePath
                   ^java.io.File (configured-file configuration path))))
        manifest (:external-publication-manifest configuration)
        manifest-file (when manifest (configured-file configuration manifest))
        _ (when manifest-file
            ;; Each external process starts from a clean publication cursor.
            ;; Old dylib paths belong to the preceding process and must not be
            ;; replayed before the new app finishes its own initialization.
            (io/make-parents manifest-file)
            (Files/writeString
             (.toPath manifest-file)
             ""
             StandardCharsets/UTF_8
             (into-array StandardOpenOption
                         [StandardOpenOption/CREATE
                          StandardOpenOption/TRUNCATE_EXISTING
                          StandardOpenOption/WRITE])))
        _ (when manifest
            (.put environment
                  "AGUAFRIA_PUBLICATION_MANIFEST"
                  (.getAbsolutePath ^java.io.File manifest-file)))
        process (.start builder)
        view (external-program-view process command working-directory)]
    (swap! projects update project-id
           (fn [current]
             (-> current
                 (assoc :status :running :updated-at-ms
                        (System/currentTimeMillis))
                 (assoc-in [:internal :external-process] process)
                 (assoc :program view))))
    (assoc view :project-id project-id :runtime-id runtime-id)))

(defn bootstrap-project!
  "Build and register an existing Zig project's declaration graph in memory.

  `:bootstrap? false` keeps the zero-configuration single-file workflow. A
  configured existing project sets it true so imports, cycles, generated build
  modules, and later unsaved edits share one authoritative graph."
  [project-id]
  (let [project-id (str project-id)
        {:keys [configuration] :as project} (project! project-id)]
    (if-not (true? (:bootstrap? configuration))
      {:project-id project-id :runtime-id runtime-id :status :skipped}
      (if-let [summary (:bootstrap-summary project)]
        (assoc summary :cached? true)
        (let [started (System/currentTimeMillis)
            tree
            (or (get-in project [:internal :source-tree])
                (convert/load-source-tree!
                 (:project-root configuration)
                 (select-keys configuration
                              [:namespace-prefix :exclude-directories
                               :capture-build-modules? :build-steps :build-profiles
                               :build-file :zig :cache-dir])))
            ;; Preserve the expensive immutable parse/import graph even when a
            ;; later materialization or Zig build reports an error. The user can
            ;; fix Aguafria/source code and retry in the same JVM, matching the
            ;; ordinary REPL workflow instead of paying a full project bootstrap
            ;; after every failed compiler iteration.
            _ (swap! projects assoc-in [project-id :internal :source-tree] tree)
            development-options (:development-materialization configuration)
            development-output
            (when development-options
              (configured-file
               configuration
               (or (:output development-options)
                   ".aguafria/development-project")))
            development
            (when development-options
              (convert/materialize-development-tree!
               tree development-output
               (merge
                (select-keys configuration [:zig])
                (dissoc development-options :output))))
            external-publication
            (when development
              (runtime/enable-external-publication!
               (map :module (:development-files development))))
            development-build
            (when development-options
              (run-development-build!
               configuration development-output (:development-build configuration)))
            completed (System/currentTimeMillis)
            summary {:project-id project-id
                     :runtime-id runtime-id
                     :status :ready
                     :file-count (:file-count tree)
                     :declaration-count (:declaration-count tree)
                     :build-profiles (:build-profiles tree)
                     :development
                     (some-> development
                             (select-keys [:output-root
                                           :development-module-count
                                           :loader-relative-path
                                           :elapsed-ms]))
                     :development-build development-build
                     :external-publication
                     (some-> external-publication
                             (select-keys [:enabled-module-count]))
                     :elapsed-ms (- completed started)}]
        (swap! projects assoc project-id
               (assoc project
                      :status :ready
                      :bootstrapped-at-ms completed
                      :bootstrap-summary summary
                      :internal {:source-tree tree
                                 :development development}))
          summary)))))

(defn start-program!
  "Start the configured native development program.

  `:entry-point` starts a Zig main inside this authoritative JVM. An explicit
  `:external-program` starts a platform-owned UI process (for example an
  AppKit application) that consumes Aguafria generation publications through
  the configured development loader. Repeated calls return the existing host
  or exact child process."
  [project-id]
  (let [project-id (str project-id)
        {:keys [configuration] :as project} (project! project-id)
        entry-point (:entry-point configuration)
        external-process (get-in project [:internal :external-process])]
    (cond
      (false? (:start-program? configuration))
      {:project-id project-id :runtime-id runtime-id :status :skipped
       :reason :disabled}

      (and external-process (.isAlive ^Process external-process))
      (assoc (external-program-view
              external-process
              (external-program-command configuration)
              (get-in project [:program :working-directory]))
             :project-id project-id :runtime-id runtime-id)

      (:external-program configuration)
      (start-external-program! project-id project)

      (nil? entry-point)
      {:project-id project-id :runtime-id runtime-id :status :skipped
       :reason :no-entry-point}

      (get-in project [:internal :host])
      (assoc (runtime/host-info (get-in project [:internal :host]))
             :project-id project-id :runtime-id runtime-id)

      :else
      (let [_ (when (and (true? (:bootstrap? configuration))
                         (nil? (get-in project [:internal :source-tree])))
                (bootstrap-project! project-id))
            project (project! project-id)
            entry-file (canonical-file (:project-root configuration) entry-point)
            module (module-for-uri project-id (.getAbsolutePath entry-file))
            function-name (str (or (:entry-function configuration) "main"))
            qualified-name (symbol module function-name)
            arguments (vec (or (:arguments configuration) []))
            options (select-keys configuration
                                 [:argv0 :share-state? :stack-size-bytes])
            host (runtime/start-process-main! qualified-name arguments options)]
        (swap! projects update project-id
               (fn [current]
                 (-> current
                     (assoc :status :running :updated-at-ms
                            (System/currentTimeMillis))
                     (assoc-in [:internal :host] host)
                     (assoc :program {:entry-point (.getAbsolutePath entry-file)
                                      :entry-function function-name
                                      :qualified-name (str qualified-name)}))))
        (assoc (runtime/host-info host)
               :project-id project-id :runtime-id runtime-id)))))

(defn program-status
  "Return the configured native development-program state for a project."
  [project-id]
  (let [project-id (str project-id)
        project (project! project-id)]
    (cond
      (get-in project [:internal :external-process])
      (let [process (get-in project [:internal :external-process])]
        (assoc (external-program-view
                process
                (external-program-command (:configuration project))
                (get-in project [:program :working-directory]))
               :project-id project-id :runtime-id runtime-id))

      (get-in project [:internal :host])
      (let [host (get-in project [:internal :host])]
      (assoc (runtime/host-info host)
             :project-id project-id :runtime-id runtime-id
             :program (:program project)))

      :else
      {:project-id project-id :runtime-id runtime-id
       :status :not-started :program (:program project)})))

(defn stop-program!
  "Request termination of the exact external program owned by a project.

  The indexed Zig graph and authoritative JVM remain alive, so starting the
  UI again does not repeat project bootstrap. JVM-owned native hosts currently
  retain their existing lifecycle semantics."
  [project-id]
  (let [project-id (str project-id)
        project (project! project-id)]
    (if-let [^Process process (get-in project [:internal :external-process])]
      (do
        (when (.isAlive process) (.destroy process))
        (swap! projects update project-id
               (fn [current]
                 (-> current
                     (update :internal dissoc :external-process)
                     (assoc :status :ready
                            :updated-at-ms (System/currentTimeMillis)
                            :program (assoc (:program current)
                                            :status :stopping)))))
        {:project-id project-id :runtime-id runtime-id
         :kind :external :status :stopping :pid (.pid process)})
      {:project-id project-id :runtime-id runtime-id
       :status :not-started})))

(defn- current-native-generation
  [module-info]
  (or (some #(when (:current? %) %) (:native-generations module-info))
      (last (:native-generations module-info))))

(defn- publish-external-generation!
  [project ticket module-info]
  (when-let [manifest-path
             (get-in project [:configuration :external-publication-manifest])]
    (when-let [{:keys [generation library-path dispatch
                       publication-epoch-setter] :as external}
               (runtime/external-generation-info (:module ticket)
                                                 (:logical-ids ticket))]
      (when (seq dispatch)
        (let [manifest (configured-file (:configuration project) manifest-path)
            publication
            (str "B\t" generation "\t" (:module ticket) "\t"
                 library-path "\t" (count dispatch) "\t"
                 publication-epoch-setter "\n"
                 (apply str
                        (map (fn [{:keys [getter setter]}]
                               (str "S\t" getter "\t" setter "\n"))
                             dispatch))
                 "E\t" generation "\n")]
        (locking external-publication-lock
          (io/make-parents manifest)
          (Files/writeString
           (.toPath manifest)
           publication
           StandardCharsets/UTF_8
           (into-array StandardOpenOption
                       [StandardOpenOption/CREATE
                        StandardOpenOption/WRITE
                        StandardOpenOption/APPEND])))
          {:manifest (.getAbsolutePath manifest)
           :generation generation
           :module (:module ticket)
           :library-path library-path
           :dispatch (:dispatch external)})))))

(defn- affected-publication
  [runtime-publication]
  (or (:affected runtime-publication)
      ;; `runtime/await!` deliberately returns the current inspectable module
      ;; state. If an async publication won the race before the editor reached
      ;; the barrier, its transitive result is retained here rather than in the
      ;; already-delivered promise value.
      (:last-dependent-publication runtime-publication)))

(defn- affected-external-modules
  [root-module runtime-publication]
  (let [affected (affected-publication runtime-publication)]
    (->> (concat
          (when-not (:last-publication-transit-only? runtime-publication)
            [root-module])
          ;; Metadata-only comptime/type transit nodes have no new native
          ;; image. Publishing their preceding library generation again is
          ;; both misleading and can overwrite a newer concrete consumer in
          ;; an external process.
          (keep (fn [{:keys [module compiled?]}]
                  (when compiled? module))
                (:exact-results affected))
          (mapcat :modules (:publications affected)))
         (remove nil?)
         (map str)
         distinct
         vec)))

(defn- external-logical-ids-by-module
  [runtime-publication]
  (reduce
   (fn [result {:keys [modules external-logical-ids]}]
     (if (some? external-logical-ids)
       (let [by-module (group-by first external-logical-ids)]
         (reduce (fn [result module]
                   (assoc result module (set (get by-module module))))
                 result modules))
       result))
   {}
   (:publications (affected-publication runtime-publication))))

(defn- publish-affected-external-generations!
  [project root-module runtime-publication]
  (let [logical-ids-by-module
        (external-logical-ids-by-module runtime-publication)
        publications
        (->> (affected-external-modules root-module runtime-publication)
             (keep (fn [module]
                     (publish-external-generation!
                      project {:module module
                               :logical-ids
                               (get logical-ids-by-module module)}
                      (runtime/module-info module))))
             vec)]
    (case (count publications)
      0 nil
      1 (first publications)
      {:publication-count (count publications)
       :modules (mapv :module publications)
       :publications publications})))

(defn- project!
  [project-id]
  (or (get @projects (str project-id))
      (throw (ex-info "Unknown Aguafria editor project"
                      {:aguafria/phase :zig-editor-project
                       :project-id (str project-id)
                       :known-projects (sort (keys @projects))}))))

(defn- uri-file
  [uri]
  (try
    (let [parsed (URI. (str uri))]
      (if (= "file" (.getScheme parsed))
        (io/file parsed)
        (io/file (str uri))))
    (catch Throwable _
      (io/file (str uri)))))

(defn- module-segment
  [value]
  (let [segment (-> (str value)
                    (str/replace #"\.zig$" "")
                    (str/replace "_" "-")
                    (str/replace #"[^A-Za-z0-9.-]" "-")
                    (str/replace #"-+" "-")
                    (str/replace #"^[.-]+|[.-]+$" ""))]
    (if (str/blank? segment) "root" segment)))

(defn module-for-uri
  "Return the stable Aguafria module name for a Zig document."
  [project-id uri]
  (let [{:keys [configuration]} (project! project-id)
        root (.toPath (.getCanonicalFile (io/file (:project-root configuration))))
        file (.toPath (.getCanonicalFile (uri-file uri)))]
    (when-not (.startsWith file root)
      (throw (ex-info "Zig document is outside the configured project root"
                      {:aguafria/phase :zig-editor-project
                       :project-id (str project-id)
                       :project-root (str root)
                       :uri (str uri)
                       :path (str file)})))
    (let [relative (.relativize root file)
          segments (map (comp module-segment str)
                        (iterator-seq (.iterator relative)))]
      (str (:namespace-prefix configuration) "."
           (str/join "." segments)))))

(defn- normalize-position
  [position]
  (when position
    {:line (long (or (:line position) (get position "line") 0))
     :character (long (or (:character position) (get position "character") 0))}))

(defn- normalize-range
  [range]
  (when range
    {:start (normalize-position (or (:start range) (get range "start")))
     :end (normalize-position (or (:end range) (get range "end")))}))

(defn- position<=
  [left right]
  (or (< (:line left) (:line right))
      (and (= (:line left) (:line right))
           (<= (:character left) (:character right)))))

(defn- ranges-overlap?
  [left right]
  (and (position<= (:start left) (:end right))
       (position<= (:start right) (:end left))))

(defn- selected-spans
  [spans mode position range]
  (case mode
    :file spans
    :changed spans
    :selection
    (let [range (or range {:start position :end position})]
      (filterv #(ranges-overlap? (:leading-range %) range) spans))
    :declaration
    (let [position (or position (get-in range [:start]) {:line 0 :character 0})]
      (if-let [selected
               (some (fn [{:keys [leading-range] :as span}]
                       (when (and (position<= (:start leading-range) position)
                                  (position<= position (:end leading-range)))
                         span))
                     spans)]
        [selected]
        (cond-> [] (seq spans) (conj (last spans)))))
    (throw (ex-info "Unsupported Zig editor evaluation mode"
                    {:aguafria/phase :zig-editor-input
                     :mode mode
                     :supported [:declaration :selection :file :changed]}))))

(defn- validate-document-version!
  [project uri version source-hash base-source-hash]
  (when-let [current (get-in project [:documents uri])]
    (when (if base-source-hash
            (not= (str base-source-hash) (:source-hash current))
            (or (< version (:document-version current))
                (and (= version (:document-version current))
                     (not= source-hash (:source-hash current)))))
      (throw (ex-info "Refusing stale Zig editor source"
                      {:aguafria/phase :zig-editor-stale-source
                       :uri uri
                       :document-version version
                       :source-hash source-hash
                       :base-source-hash base-source-hash
                       :accepted-document-version (:document-version current)
                       :accepted-source-hash (:source-hash current)
                       :accepted-generation (:accepted-generation current)})))))

(defn- remember-ticket!
  [ticket]
  (swap! tickets assoc (:ticket-id ticket) ticket)
  (swap! ticket-order
         (fn [order]
           (let [order (conj order (:ticket-id ticket))
                 retained (vec (take-last ticket-history-limit order))
                 retained-set (set retained)]
             (swap! tickets #(select-keys % retained-set))
             retained)))
  ticket)

(defn- position-offset
  [source {:keys [line character]}]
  (let [lines (str/split source #"\n" -1)
        line (-> (long (or line 0)) (max 0) (min (dec (count lines))))]
    (+ (reduce + 0 (map #(inc (count %)) (take line lines)))
       (min (long (or character 0)) (count (nth lines line ""))))))

(defn- offset-position
  [source offset]
  (let [prefix (subs source 0 (-> (long offset) (max 0) (min (count source))))
        lines (str/split prefix #"\n" -1)]
    {:line (dec (count lines))
     :character (count (peek lines))}))

(defn- diagnostic-token-range
  [source declaration-range compiler-message]
  (when-let [[_ token] (and source compiler-message
                            (re-find #"(?:identifier|symbol|field|member) '([^']+)'"
                                     compiler-message))]
    (let [start (position-offset source (:start declaration-range))
          end (position-offset source (:end declaration-range))
          found (.indexOf ^String source ^String token (int start))]
      (when (and (<= start found) (<= (+ found (count token)) end))
        {:start (offset-position source found)
         :end (offset-position source (+ found (count token)))}))))

(defn- diagnostic
  [error context]
  (let [data (ex-data error)
        first-error (first (:errors data))
        parsed-diagnostics (:diagnostics data)
        parsed-diagnostic (first parsed-diagnostics)
        retained-generation
        (some-> (runtime/module-info (:module context)) :published-generation)]
    {:source "Aguafria Hot Reload"
     :uri (:uri context)
     :document-version (:document-version context)
     :source-hash (:source-hash context)
     :range (or (diagnostic-token-range (:source context) (:range context)
                                        (:message parsed-diagnostic))
                (some-> parsed-diagnostic :aguafria/source
                        (select-keys [:range]) :range)
                (when first-error
                  (let [[_ _ line column] first-error]
                    {:start {:line (max 0 (dec line))
                             :character (max 0 (dec column))}
                     :end {:line (max 0 (dec line))
                           :character (max 0 column)}}))
                (:range context)
                {:start {:line 0 :character 0}
                 :end {:line 0 :character 1}})
     :severity :error
     :code (str (or (:aguafria/phase data) :zig-editor-error))
     :message (.getMessage ^Throwable error)
     :module (:module context)
     :active-generation retained-generation
     :old-behavior-retained? (some? retained-generation)
     :related-information
     (mapv (fn [{:keys [file line column message]}]
             {:message message
              :location {:uri (str file)
                         :range {:start {:line (max 0 (dec (or line 1)))
                                         :character (max 0 (dec (or column 1)))}
                                 :end {:line (max 0 (dec (or line 1)))
                                       :character (max 0 (or column 1))}}}})
           parsed-diagnostics)
     :details (dissoc data :diagnostics :command)}))

(defn evaluate!
  "Parse, structurally convert, and publish Zig from an unsaved editor buffer.

  Modes are `:declaration`, `:selection`, `:file`, and `:changed`. Returns a
  ticket after scheduling; call `await!` to establish the publication barrier."
  [{:keys [project-id uri source document-version mode position range request-id
           base-source-hash]
    :or {mode :declaration document-version 0}
    :as request}]
  (let [project-id (str project-id)
        project (project! project-id)
        uri (str uri)
        source (str source)
        mode (keyword mode)
        document-version (long document-version)
        requested-source-hash (:source-hash request)
        module (module-for-uri project-id uri)
        position (normalize-position position)
        range (normalize-range range)
        request-id (str (or request-id (UUID/randomUUID)))
        context {:project-id project-id
                 :runtime-id runtime-id
                 :uri uri
                 :document-version document-version
                 :module module
                 :range range
                 :request-id request-id
                 :source source}
        diagnostic-context (volatile! context)]
    (try
      (let [source-byte-count
            (alength (.getBytes source StandardCharsets/UTF_8))
            max-source-bytes
            (long (get-in project [:configuration :max-source-bytes]
                          default-max-source-bytes))
            _ (when (> source-byte-count max-source-bytes)
                (throw (ex-info "Zig editor buffer exceeds the configured source limit"
                                {:aguafria/phase :zig-editor-input
                                 :uri uri
                                 :source-bytes source-byte-count
                                 :max-source-bytes max-source-bytes})))
            source-tree (get-in project [:internal :source-tree])
            rendered (if source-tree
                       (convert/render-planned-source source-tree
                                                      (uri-file uri) source)
                       (convert/render-source source
                                              {:namespace (symbol module)
                                               :path uri}))
            source-hash (:source-hash rendered)
            _ (when (and requested-source-hash
                         (not= (str requested-source-hash) source-hash))
                (throw (ex-info "Refusing Zig editor source whose hash changed in transit"
                                {:aguafria/phase :zig-editor-stale-source
                                 :uri uri
                                 :document-version document-version
                                 :requested-source-hash requested-source-hash
                                 :actual-source-hash source-hash})))
            _ (validate-document-version! project uri document-version source-hash
                                          base-source-hash)
            spans (selected-spans (:spans rendered) mode position range)
            _ (vreset! diagnostic-context
                       (assoc context :range (or (:range (first spans)) range)))
            orders (set (map :source-order spans))
            declarations (filterv #(contains? orders (:source-order %))
                                  (:declarations rendered))
            _ (when (and (not= :file mode) (empty? declarations))
                (throw (ex-info "No complete Zig declaration is selected"
                                {:aguafria/phase :zig-editor-selection
                                 :uri uri :position position :range range})))
            result
            (if (and (= :declaration mode)
                     (= 1 (count declarations)))
              ;; Preserve the previously published descriptor while planning
              ;; a single-Var edit. Seeding a complete replacement batch first
              ;; erased the old ABI before the runtime could recognize a
              ;; breaking signature and select its isolated live slice.
              (runtime/register-declaration! (first declarations))
              (runtime/register-batch!
               declarations
               {:module module
                :compile? true
                :replace? (contains? #{:file :changed} mode)}))
            ticket-id (str (UUID/randomUUID))
            ticket
            (with-meta
              {:ticket-id ticket-id
               :request-id request-id
               :project-id project-id
               :runtime-id runtime-id
               :uri uri
               :document-version document-version
               :source-hash source-hash
               :module module
               :declarations (mapv #(str (:name %)) declarations)
               :declaration-keys (mapv :declaration-key declarations)
               :ranges (mapv :range spans)
               :mode mode
               :status (if (:pending? result) :queued :published)
               :requested-generation (:generation result)
               :accepted-at-ms (System/currentTimeMillis)
               :runtime-result result}
              ;; Metadata stays inside this JVM and is not traversed by the
              ;; bencode wire encoder. It gives an async compiler failure the
              ;; exact unsaved source without echoing or retaining it in
              ;; public history data.
              {:diagnostic-context @diagnostic-context})]
        (swap! projects assoc-in [project-id :documents uri]
               {:document-version document-version
                :source-hash source-hash
                :module module
                :accepted-generation (:requested-generation ticket)
                :accepted-at-ms (:accepted-at-ms ticket)})
        (remember-ticket! ticket))
      (catch Throwable error
        (let [source-hash (sha256 source)
              ticket {:ticket-id (str (UUID/randomUUID))
                      :request-id request-id
                      :project-id project-id
                      :runtime-id runtime-id
                      :uri uri
                      :document-version document-version
                      :source-hash source-hash
                      :module module
                      :mode mode
                      :status :failed
                      :failed-at-ms (System/currentTimeMillis)
                      :diagnostics [(diagnostic error
                                                (assoc @diagnostic-context
                                                       :source-hash source-hash))]}]
          (remember-ticket! ticket))))))

(defn register-source!
  "Register selected declarations from an unsaved Zig buffer."
  [request]
  (evaluate! request))

(defn reconcile-source!
  "Atomically reconcile all declarations and removals from one Zig buffer."
  [request]
  (evaluate! (assoc request :mode :file)))

(defn await!
  "Wait until an evaluation ticket is published or failed."
  [ticket-id]
  (let [ticket-id (str ticket-id)
        ticket (or (get @tickets ticket-id)
                   (throw (ex-info "Unknown Aguafria editor ticket"
                                   {:aguafria/phase :zig-editor-ticket
                                    :ticket-id ticket-id})))]
    (if (contains? #{:failed :cancelled} (:status ticket))
      ticket
      (try
        (let [runtime-publication (runtime/await! (:module ticket))
              module-info (runtime/module-info (:module ticket))
              project (project! (:project-id ticket))
              external-publication
              (publish-affected-external-generations!
               project (:module ticket) runtime-publication)
              completed
              (with-meta
                (assoc ticket
                       :status :published
                       :published-generation (:published-generation module-info)
                       :completed-at-ms (System/currentTimeMillis)
                       ;; Never retain the complete internal runtime module in
                       ;; an editor ticket. Besides being noisy at the REPL, it
                       ;; contains generated sources, compiler commands, and
                       ;; native loader handles. The module remains available
                       ;; through `runtime/module-info`; tickets need only the
                       ;; compact publication outcome.
                       :publication-result
                       (select-keys runtime-publication
                                    [:status :module :generation :published?
                                     :affected :last-dependent-publication])
                       :external-publication external-publication
                       :module-info
                       (select-keys module-info
                                    [:module :requested-generation
                                     :published-generation :pending?
                                     :native-generation-count
                                     :retired-generation-count
                                     :native-generations]))
                nil)]
          (swap! tickets assoc ticket-id completed)
          completed)
        (catch Throwable error
          (let [failed
                (with-meta
                  (assoc ticket
                         :status :failed
                         :failed-at-ms (System/currentTimeMillis)
                         :diagnostics
                         [(diagnostic error
                                      (or (:diagnostic-context (meta ticket))
                                          ticket))])
                  nil)]
            (swap! tickets assoc ticket-id failed)
            failed))))))

(defn ticket
  "Return the latest view of one evaluation ticket."
  [ticket-id]
  (get @tickets (str ticket-id)))

(defn interrupt!
  "Best-effort cancellation for one exact editor ticket.

  Only queued debounce work is cancellable. Once Zig compilation or atomic
  publication starts, the result explicitly reports `:already-running`."
  [ticket-id]
  (let [ticket-id (str ticket-id)
        ticket (or (get @tickets ticket-id)
                   (throw (ex-info "Unknown Aguafria editor ticket"
                                   {:aguafria/phase :zig-editor-ticket
                                    :ticket-id ticket-id})))
        status (:status ticket)]
    (if (contains? #{:published :failed :cancelled} status)
      {:ticket-id ticket-id :status status :terminal? true}
      (let [result (runtime/cancel-pending! (:module ticket)
                                            (:requested-generation ticket))]
        (if (= :cancelled (:status result))
          (let [cancelled (with-meta
                            (assoc ticket :status :cancelled
                                   :cancelled-at-ms (System/currentTimeMillis))
                            nil)]
            (swap! tickets assoc ticket-id cancelled)
            cancelled)
          (assoc result :ticket-id ticket-id :terminal? false))))))

(defn invoke!
  "Invoke a latest native Zig function by module/name for editor inspection."
  [project-id uri function-name arguments]
  (let [module (module-for-uri project-id uri)]
    (runtime/invoke! (symbol module (str function-name)) (vec arguments))))

(defn inspect!
  "Return one selected live declaration and its exact Clojure/native value.

  Constants and variables use Aguafria's normal native decoder, including
  arbitrary-width integers and composite layouts. Functions are described but
  are not invoked implicitly; `invoke!` remains the explicit operation for
  calls with effects."
  [project-id uri source position]
  (let [project-id (str project-id)
        uri (str uri)
        module (module-for-uri project-id uri)
        source (str source)
        rendered
        (if-let [source-tree (get-in (project! project-id)
                                     [:internal :source-tree])]
          (convert/render-planned-source source-tree (uri-file uri) source)
          (convert/render-source source {:namespace (symbol module) :path uri}))
        span (first (selected-spans (:spans rendered) :declaration
                                    (normalize-position position) nil))
        parsed (some #(when (= (:source-order %) (:source-order span)) %)
                     (:declarations rendered))
        live
        (some #(when (or (= (:declaration-key parsed) (:declaration-key %))
                         (= (str (:name parsed)) (str (:name %))))
                 %)
              (:definitions (runtime/module-info module)))]
    (when-not parsed
      (throw (ex-info "No complete Zig declaration is selected"
                      {:aguafria/phase :zig-editor-selection
                       :uri uri :position position})))
    (when-not live
      (throw (ex-info "The selected Zig declaration has not been published"
                      {:aguafria/phase :zig-editor-value
                       :module module :name (str (:name parsed))})))
    (let [root
          (case (:kind live)
            :const (runtime/declaration-root-value live)
            :var (runtime/declaration-state-value live)
            nil)
          exact-value
          (cond
            (zig-value/zig-value? root) (zig-value/decoded root)
            (zig-value/zig-type? root) (zig-value/type-info root)
            :else root)]
      {:project-id project-id
       :runtime-id runtime-id
       :uri uri
       :module module
       :range (:range span)
       :declaration
       (select-keys live
                    [:name :zig-name :kind :type :return :args :logical-id
                     :declaration-key :abi-fingerprint :schema-fingerprint
                     :implementation-fingerprint])
       :value-available? (contains? #{:const :var} (:kind live))
       :value exact-value})))

(defn source
  "Return the emitted Zig source for one editor document's live module."
  [project-id uri]
  (runtime/source (module-for-uri project-id uri)))

(defn describe
  "Return editor protocol and current runtime capabilities."
  []
  {:protocol-version protocol-version
   :runtime-id runtime-id
   :project-ids (sort (keys @projects))
   :aguafria-version "development"
   :zig-version (some-> (runtime/configuration) :zig str)
   :capabilities
   #{:eval-declaration :eval-selection :eval-file :async-publication
     :structured-diagnostics :statistics :source :invoke :exact-native-values
     :interrupt :compatible-hot-reload :versioned-breaking-change
     :external-native-program :external-generation-publication
     :lifecycle-shutdown}})

(defn project-state
  "Return lightweight reconnect state for one editor project.

  Unlike aggregate compiler statistics this is bounded by the number of open
  documents and never traverses a large project's full declaration graph."
  [project-id]
  (let [project-id (str project-id)
        project (project! project-id)]
    {:project-id project-id
     :runtime-id runtime-id
     :status (:status project)
     :documents (into (sorted-map) (:documents project))
     :native-program (program-status project-id)}))

(defn stats
  "Return serializable editor, compiler, and native-host state."
  []
  {:protocol-version protocol-version
   :runtime-id runtime-id
   :projects
   (into (sorted-map)
         (map (fn [[project-id project]]
                [project-id
                 (-> project
                     (update :documents #(into (sorted-map) %))
                     (assoc :native-program
                            (cond
                              (get-in project [:internal :external-process])
                              (program-status project-id)

                              (get-in project [:internal :host])
                              (runtime/host-info
                               (get-in project [:internal :host]))))
                     (dissoc :internal))]))
         @projects)
   :tickets (->> @ticket-order (keep @tickets) reverse vec)
   :runtime (runtime/stats)})

(defn clear!
  "Clear editor bookkeeping. Intended for tests and explicit REPL reset."
  []
  (doseq [[project-id] @projects]
    (stop-project! project-id))
  (reset! projects {})
  (reset! tickets {})
  (reset! ticket-order [])
  nil)
