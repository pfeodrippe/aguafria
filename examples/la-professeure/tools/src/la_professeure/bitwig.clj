(ns la-professeure.bitwig
  "Clojure adapter for Bitwig. Production dialogue parsing runs in native Aguafria Zig."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [la-professeure.dialogue :as dialogue])
  (:import [java.net ServerSocket InetAddress Socket]
           [java.io DataOutputStream BufferedReader InputStreamReader]))

(defonce connection (atom nil))
(defonce pending (atom {}))
(defonce status (atom {:state :stopped}))

(defn- fail-pending! [message]
  (doseq [[_ p] @pending] (deliver p {:error message})))

(defn stop! []
  (let [{:keys [server client]} @connection]
    (reset! connection nil)
    (when client (.close ^Socket client))
    (when server (.close ^ServerSocket server)))
  (fail-pending! "Adapter stopped")
  (reset! pending {})
  (reset! status {:state :stopped}))

(defn start!
  "Start a loopback-only adapter; no controller starts playback or arms inputs."
  []
  (when @connection (throw (ex-info "Adapter already started" @status)))
  (let [server (ServerSocket. 0 1 (InetAddress/getByName "127.0.0.1"))]
    (reset! connection {:server server})
    (reset! status {:state :listening :port (.getLocalPort server)})
    (future
      (try
        (while (not (.isClosed server))
          (try
            (with-open [client (.accept server)
                      reader (BufferedReader. (InputStreamReader. (.getInputStream client) "UTF-8"))]
            (swap! connection assoc :client client :output (DataOutputStream. (.getOutputStream client)))
            (swap! status #(-> % (assoc :state :connected) (dissoc :message)))
            (loop []
              (when-let [line (.readLine reader)]
                (let [message (json/read-str line :key-fn keyword)]
                  (swap! status assoc :last-message message)
                  (when-let [p (get @pending (:id message))] (deliver p message)))
                (recur))))
            (catch Exception e
              (when-not (.isClosed server)
                (swap! status assoc :message (.getMessage e)))))
          (when (identical? server (:server @connection))
            (swap! connection dissoc :client :output)
            (fail-pending! "Bitwig disconnected; inspect the project before retrying")
            (swap! status assoc :state :listening)))
        (catch java.net.SocketException _)
        (catch Throwable e
          (when (identical? server (:server @connection))
            (swap! status assoc :state :error :message (.getMessage e))))))
    @status))

(defn install!
  "Install only our controller script; preserve every other installed controller."
  [directory]
  (let [port (:port @status)
        target (io/file directory "LaProfesseure.control.js")
        resource (io/resource "LaProfesseure.control.js")]
    (when-not (and port resource) (throw (ex-info "Start the adapter and include tools/resources first" {})))
    (when (and (.exists target) (not (str/includes? (slurp target) "6adb95d0-0a52-4ddc-b230-39464902d818")))
      (throw (ex-info "Refusing to overwrite an unrecognized controller" {:file (str target)})))
    (dialogue/atomic-write! target (str "var LP_PORT = " port ";\n" (slurp resource)))
    (str target)))

(defn request! [command]
  (let [id (str (java.util.UUID/randomUUID))
        response (promise)
        output (:output @connection)
        bytes (.getBytes (json/write-str (assoc command :id id) :escape-unicode true) "US-ASCII")]
    (when-not output (throw (ex-info "Enable the Dialogue recording controller in Bitwig first" @status)))
    (swap! pending assoc id response)
    (try
      (locking output (.writeInt ^DataOutputStream output (alength bytes))
        (.write ^DataOutputStream output bytes) (.flush ^DataOutputStream output))
      (let [timeout-ms (+ 30000 (* 2500 (count (:tracks command))))
            result (deref response timeout-ms
                          {:error "Bitwig acknowledgement timed out; inspect before retrying"})]
        (when (:error result) (throw (ex-info (:error result) result))) result)
      (finally (swap! pending dissoc id)))))

(defn inspect! [] (request! {:op "status"}))

(defn save-project!
  "Ask Bitwig to save the explicitly named active project. Unsaved projects may
  open Bitwig's Save dialog; check :modified from inspect! to confirm completion."
  [project]
  (let [action (some #(when (= "Save" (:name %)) (:id %)) (:saveActions (inspect!)))]
    (when-not action (throw (ex-info "Bitwig did not expose its ordinary Save action" {})))
    (request! {:op "save-project" :project project :action action})))

(defn import-take!
  "Explicitly import a WAV into a managed track's EMPTY first launcher slot.
  Never arms, plays, records, or replaces an existing clip. Not called by sync/watch."
  [project passage-id wav]
  (let [file (.getCanonicalFile (io/file wav))]
    (when-not (and (.isFile file) (str/ends-with? (str/lower-case (.getName file)) ".wav"))
      (throw (ex-info "Choose an existing WAV file" {:path (str file)})))
    (request! {:op "import-take" :project project :key passage-id :path (str file)})))

(defn track-name [passage]
  (str "[LP:" (:recording-id passage) "] " (:voice passage) " — "
       (subs (:text passage) 0 (min 72 (count (:text passage))))))

(defn sync!
  "Apply an explicitly chosen scene to the named active Bitwig project. Never deletes tracks."
  [project scene manifest previous]
  (when-not (some #(= scene (:scene %)) (:passages manifest))
    (throw (ex-info "The selected scene has no voiced passages; nothing was synchronized"
                    {:scene scene :available-scenes (vec (distinct (map :scene (:passages manifest))))})))
  (let [prior (into {} (map (juxt :recording-id identity) (:passages previous)))
        tracks (mapv (fn [p] {:key (:recording-id p) :name (track-name p)
                             :previousName (some-> (get prior (:recording-id p)) track-name)})
                     (filter #(= scene (:scene %)) (:passages manifest)))]
    (request! {:op "sync" :project project :tracks tracks})))

(defn sync-source!
  "Compile an exported Markdown file and sync one scene to an explicitly named project.
  Successful-sync metadata is independent of the authoring manifest, so failed DAW
  connections cannot lose the previous managed names required for safe rename."
  [source directory project scene]
  (let [ack-file (io/file directory (str "bitwig-" (subs (dialogue/digest project) 0 16) ".edn"))
        previous (when (.isFile ack-file) (edn/read-string (slurp ack-file)))
        {:keys [manifest] :as prepared}
        (dialogue/compile! source directory (io/file directory "story.lpdialogue"))
        result (sync! project scene manifest previous)]
    (dialogue/atomic-write! ack-file (pr-str manifest))
    (assoc prepared :bitwig result)))

(defn watch!
  "Explicit opt-in watcher. Returns a stop function. Never changes the active Bitwig project."
  [source directory project scene]
  (let [running (atom true)
        task (future
               (loop [published nil]
                 (when @running
                   (let [next-hash
                         (try
                           (let [hash (dialogue/digest (slurp source :encoding "UTF-8"))]
                             (when (not= hash published)
                               (let [result (sync-source! source directory project scene)]
                                 (swap! status assoc :sync {:state :updated :project project
                                                           :changes (get-in result [:bitwig :changes])})))
                             hash)
                           (catch Throwable e
                             (swap! status assoc :sync {:state :error :message (.getMessage e)})
                             published))]
                     (Thread/sleep 1000)
                     (recur next-hash)))))]
    (fn [] (reset! running false) (future-cancel task))))
