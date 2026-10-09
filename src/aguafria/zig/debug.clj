(ns aguafria.zig.debug
  "Source-located type inspection. Probe compilation never supplies executable code."
  (:require [aguafria.zig.analysis :as analysis]
            [aguafria.zig.compiler-work :as compiler-work]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.nio.file Files StandardCopyOption CopyOption]
           [java.util Base64 UUID]))

(def ^:dynamic *source* nil)
(defonce ^:private reports (atom {}))
(def ^:private marker-pattern #"//aguafria-debug:([A-Za-z0-9+/=]+)")

(defn reports-map
  "Structured reports, keyed by source location, expression and observed type."
  []
  @reports)

(defn report!
  "Publish a structured type diagnostic; configuration selects :print and/or :file."
  [report {:keys [debug-output debug-report-file]
           :or {debug-output #{:print :file}
                debug-report-file ".aguafria/debug/types.edn"}}]
  (let [key (select-keys report [:file :line :column :form :type])]
    (locking reports
      (let [changed? (not= (dissoc report :diagnostics)
                           (dissoc (get @reports key) :diagnostics))]
        (swap! reports assoc key report)
        (when (contains? debug-output :file)
          (let [file (.getAbsoluteFile (io/file debug-report-file))
                _ (io/make-parents file)
                temporary (Files/createTempFile (.toPath (.getParentFile file))
                                                "types-" ".edn"
                                                (make-array java.nio.file.attribute.FileAttribute 0))]
            (try
              (spit (.toFile temporary) (pr-str {:version 1 :reports (vec (vals @reports))}))
              (Files/move temporary (.toPath file)
                          (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                                  StandardCopyOption/REPLACE_EXISTING]))
              (finally (Files/deleteIfExists temporary)))))
        (when (and (or changed? (= :jvm (:phase report)))
                   (contains? debug-output :print))
          (println (str (or (:file report) "REPL") ":" (or (:line report) 1)
                        ":" (or (:column report) 1) " [a/debug!] "
                        (or (:type report) (:message report)))))))
    report))

(defn expression
  "Emit an identity block carrying an inert, structured inspection marker."
  [form source]
  (let [location (merge *source* (select-keys (meta form) [:file :line :column]))
        file (some-> (:file location) io/file)
        probe (cond-> (assoc location :form (pr-str (second form)) :expression source)
                (and file (.isFile file))
                (assoc :source-sha256 (analysis/source-hash (slurp file))))
        encoded (.encodeToString (Base64/getEncoder) (.getBytes (pr-str probe) "UTF-8"))
        label (str "aguafria_debug_" (Integer/toUnsignedString (hash probe) 16))]
    (str label ": {\n//aguafria-debug:" encoded "\nbreak :" label " " source ";\n}")))

(defn- decode-probe [encoded]
  (edn/read-string (String. (.decode (Base64/getDecoder) ^String encoded) "UTF-8")))

(defn- source-argument [argument directory]
  (let [[_ prefix path] (re-matches #"(-M[^=]*=)(.*\.zig)" argument)
        path (or path (when (str/ends-with? argument ".zig") argument))
        file (when path (io/file path))
        file (when file (if (.isAbsolute file) file (io/file directory path)))]
    (when (and file (.isFile file))
      {:prefix (or prefix "") :file file})))

(defn- activate-markers [source probes]
  (str/replace source marker-pattern
               (fn [[_ encoded]]
                 (let [probe (decode-probe encoded)
                       id (Integer/toUnsignedString (hash probe) 16)]
                   (swap! probes assoc id probe)
                   (str "@compileLog(\"aguafria.debug:" id ":\" ++ @typeName(@TypeOf("
                        (:expression probe) ")));")))))

(defn- inspection-argument [argument directory session probes copies]
  (if-let [{:keys [prefix file]} (source-argument argument directory)]
    (let [source (slurp file)]
      (if (str/includes? source "//aguafria-debug:")
        (let [copy (io/file (.getParentFile file)
                            (str ".debug-" session "-" (.getName file)))]
          (swap! copies conj copy)
          (spit copy (activate-markers source probes))
          (str prefix (.getAbsolutePath copy)))
        argument))
    argument))

(defn- no-output-command [command]
  (let [arguments (into [] (remove #(str/starts-with? % "-femit-")) command)]
    (cond-> (conj arguments "-fno-emit-bin")
      (and (= "test" (second command))
           (not (some #{"--test-no-exec"} command)))
      (conj "--test-no-exec"))))

(defn- report-compiler-output! [stderr probes options]
  (let [log (second (str/split stderr #"Compile Log Output:\r?\n" 2))
        found (into #{}
                    (map (fn [[_ id type]] [id (edn/read-string (str "\"" type "\""))]))
                    (re-seq #"\"aguafria\.debug:([0-9a-f]+):((?:\\.|[^\"\\])*)\"" (or log "")))
        analyzed (set (map first found))
        probe-error? (re-find #"(?m)error: (?!found compile log statement)" stderr)]
    ;; One generic expression can have several compiler-confirmed types.
    ;; Lazy, unused declarations produce no log; do not invent a type for them.
    (doseq [[id type] found :let [probe (get probes id)] :when probe]
      (report! (merge (dissoc probe :expression)
                      {:phase :compile :kind :type :status :ok :type type})
               options))
    (when probe-error?
      (doseq [[id probe] probes :when (not (contains? analyzed id))]
        (report! (merge (dissoc probe :expression)
                        {:phase :compile :kind :type :status :unavailable
                         :message "Type inspection failed; see :diagnostics in a/debug-reports."
                         :diagnostics stderr})
                 options)))))

(defn inspect-command!
  "Compile inspection-only copies of marked modules, with no binary output.
  Original source files and compiler diagnostics are never rewritten."
  [command directory options]
  (when (contains? #{"build-lib" "build-exe" "build-obj" "test"} (second command))
    (let [probes (atom {})
          copies (atom [])
          session (str (UUID/randomUUID))]
      (try
        (let [arguments (mapv #(inspection-argument % directory session probes copies) command)]
          (when (seq @probes)
            (let [command (no-output-command arguments)
                  result (binding [compiler-work/*phase* :debug-inspection]
                           (compiler-work/run-command!
                            command #(apply shell/sh (concat command [:dir directory]))))]
              (report-compiler-output! (:err result) @probes options))))
        (finally
          (doseq [file @copies]
            (Files/deleteIfExists (.toPath file))))))))
