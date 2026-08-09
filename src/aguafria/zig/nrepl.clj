(ns aguafria.zig.nrepl
  "nREPL middleware for pure Zig editor evaluation."
  (:require [aguafria.zig.editor :as editor]
            [aguafria.zig.value :as zig-value]
            [clojure.string :as str]
            [nrepl.middleware :refer [set-descriptor!]]
            [nrepl.misc :as misc]
            [nrepl.transport :as transport])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(defonce ^:private shutdown-handlers (atom {}))

(defn register-shutdown-handler!
  "Register lifecycle control for one exact editor runtime."
  [runtime-id handler]
  (swap! shutdown-handlers assoc (str runtime-id) handler)
  nil)

(defn unregister-shutdown-handler!
  "Forget lifecycle control for one exact editor runtime."
  [runtime-id]
  (swap! shutdown-handlers dissoc (str runtime-id))
  nil)

(defn- schedule-shutdown!
  [runtime-id]
  (if-let [shutdown! (get @shutdown-handlers (str runtime-id))]
    (do
      ;; Return the authenticated response before closing its own transport.
      (future
        (Thread/sleep 25)
        (shutdown!))
      true)
    (throw (ex-info "This nREPL does not own the Aguafria server lifecycle"
                    {:aguafria/phase :zig-editor-lifecycle
                     :runtime-id runtime-id}))))

(defn- wire-value
  [value]
  (cond
    (nil? value) ""
    (true? value) 1
    (false? value) 0
    (zig-value/zig-value? value) (wire-value (zig-value/decoded value))
    (zig-value/zig-type? value)
    {"zig/value-kind" "type"
     "descriptor" (wire-value (zig-value/type-info value))}
    (zig-value/zig-pointer? value)
    {"zig/value-kind" "pointer"
     "address" (Long/toUnsignedString (long (zig-value/pointer-address value)))
     "type" (wire-value (zig-value/pointer-type value))}
    (string? value) value
    (integer? value)
    (let [integer (biginteger value)
          safe-limit (biginteger 9007199254740991)]
      (if (and (not (neg? (.compareTo integer (.negate safe-limit))))
               (not (pos? (.compareTo integer safe-limit))))
        value
        {"zig/value-kind" "integer"
         "decimal" (str value)}))
    (number? value) (str value)
    (or (keyword? value) (symbol? value)) (str value)
    (map? value) (into {} (map (fn [[key item]]
                                 [(if (keyword? key) (name key) (str key))
                                  (wire-value item)]))
                       value)
    (set? value) (mapv wire-value (sort-by str value))
    (sequential? value) (mapv wire-value value)
    :else (str value)))

(defn- token-valid?
  [message]
  (let [expected (System/getProperty "aguafria.editor.token")
        actual (some-> (or (:token message) (get message "token")) str)]
    (or (str/blank? expected)
        (and actual
             (MessageDigest/isEqual
              (.getBytes expected StandardCharsets/UTF_8)
              (.getBytes actual StandardCharsets/UTF_8))))))

(defn- require-token!
  [message]
  (when-not (token-valid? message)
    (throw (ex-info "Aguafria editor authentication failed"
                        {:aguafria/phase :zig-editor-authentication}))))

(defn- require-runtime!
  [message]
  (let [expected (:runtime-id (editor/describe))
        actual (some-> (or (:runtime-id message) (get message "runtime-id")) str)]
    (when-not (= expected actual)
      (throw (ex-info "Aguafria editor runtime identity mismatch"
                      {:aguafria/phase :zig-editor-runtime-identity
                       :expected-runtime-id expected
                       :actual-runtime-id actual})))))

(defn- require-protocol!
  [message]
  (let [actual (or (:protocol-version message)
                   (get message "protocol-version"))]
    (when-not (= 1 (long (or actual -1)))
      (throw (ex-info "Unsupported Aguafria editor protocol version"
                      {:aguafria/phase :zig-editor-protocol
                       :expected-protocol-version 1
                       :actual-protocol-version actual})))))

(defn- value
  [message key]
  (or (get message key) (get message (name key))))

(defn- response!
  [message result]
  (transport/send (:transport message)
                  (misc/response-for message
                                     :aguafria/result (wire-value result)
                                     :status :done)))

(defn- error-response!
  [message error]
  (transport/send
   (:transport message)
   (misc/response-for
    message
    :aguafria/error
    (wire-value {:message (.getMessage ^Throwable error)
                 :class (.getName (class error))
                 :data (ex-data error)})
    :status #{:error :done})))

(defn- evaluation-message
  [message]
  {:project-id (value message :project-id)
   :uri (value message :uri)
   :source (value message :source)
   :document-version (long (or (value message :document-version) 0))
   :mode (keyword (or (value message :mode) "declaration"))
   :position (value message :position)
   :range (value message :range)
   :source-hash (value message :source-hash)
   :base-source-hash (value message :base-source-hash)
   :request-id (or (value message :request-id) (:id message))})

(defn wrap-editor
  "Handle Aguafria's explicit editor protocol operations."
  [handler]
  (fn [{:keys [op] :as message}]
    (if-not (str/starts-with? (str op) "aguafria/")
      (handler message)
      (try
        (when-not (= op "aguafria/describe")
          (require-protocol! message)
          (require-token! message)
          (require-runtime! message))
        (case op
          "aguafria/describe"
          (response! message (editor/describe))

          "aguafria/start-project"
          (response! message
                     (editor/start-project!
                      (value message :workspace-root)
                      (or (value message :configuration) {})))

          "aguafria/stop-project"
          (response! message
                     (editor/stop-project! (value message :project-id)))

          "aguafria/bootstrap-project"
          (response! message
                     (editor/bootstrap-project! (value message :project-id)))

          "aguafria/start-program"
          (response! message
                     (editor/start-program! (value message :project-id)))

          "aguafria/program-status"
          (response! message
                     (editor/program-status (value message :project-id)))

          "aguafria/project-state"
          (response! message
                     (editor/project-state (value message :project-id)))

          "aguafria/stop-program"
          (response! message
                     (editor/stop-program! (value message :project-id)))

          "aguafria/eval-zig"
          (response! message (editor/evaluate! (evaluation-message message)))

          "aguafria/eval-zig-file"
          (response! message
                     (editor/evaluate! (assoc (evaluation-message message)
                                              :mode :file)))

          "aguafria/await"
          (response! message (editor/await! (value message :ticket-id)))

          "aguafria/interrupt"
          (response! message (editor/interrupt! (value message :ticket-id)))

          "aguafria/status"
          (response! message
                     (if-let [ticket-id (value message :ticket-id)]
                       (editor/ticket ticket-id)
                       (editor/stats)))

          "aguafria/source"
          (response! message
                     {:source (editor/source (value message :project-id)
                                             (value message :uri))})

          "aguafria/value"
          (response! message
                     (editor/inspect! (value message :project-id)
                                      (value message :uri)
                                      (value message :source)
                                      (value message :position)))

          "aguafria/invoke"
          (response! message
                     {:value (editor/invoke! (value message :project-id)
                                             (value message :uri)
                                             (value message :function)
                                             (or (value message :arguments) []))})

          "aguafria/history"
          (response! message (:tickets (editor/stats)))

          "aguafria/shutdown"
          (do
            (response! message {:status :stopping
                                :runtime-id (:runtime-id (editor/describe))})
            (schedule-shutdown! (value message :runtime-id)))

          (throw (ex-info "Unknown Aguafria editor operation"
                          {:aguafria/phase :zig-editor-protocol
                           :op op})))
        (catch Throwable error
          (error-response! message error))))))

(set-descriptor!
 #'wrap-editor
 {:requires #{}
  :expects #{}
  :handles
  {"aguafria/describe" {:doc "Describe Aguafria's pure Zig editor protocol."}
   "aguafria/start-project" {:doc "Register an editor-owned Zig project."}
   "aguafria/stop-project" {:doc "Forget an editor-owned Zig project."}
   "aguafria/bootstrap-project" {:doc "Load an existing Zig graph in memory."}
   "aguafria/start-program" {:doc "Start the configured Zig main in this JVM."}
   "aguafria/program-status" {:doc "Inspect the configured native program."}
   "aguafria/project-state" {:doc "Read bounded reconnect/document state."}
   "aguafria/stop-program" {:doc "Stop only the owned native UI program."}
   "aguafria/eval-zig" {:doc "Evaluate selected Zig declarations."}
   "aguafria/eval-zig-file" {:doc "Reconcile and evaluate one Zig file."}
   "aguafria/await" {:doc "Wait for native publication of one ticket."}
   "aguafria/interrupt" {:doc "Cancel an exact evaluation when still queued."}
   "aguafria/status" {:doc "Read ticket or aggregate runtime status."}
   "aguafria/source" {:doc "Read the emitted Zig source for a document."}
   "aguafria/value" {:doc "Inspect an exact live Zig const or var value."}
   "aguafria/invoke" {:doc "Invoke a live native Zig function."}
   "aguafria/history" {:doc "Read bounded publication history."}
   "aguafria/shutdown" {:doc "Gracefully stop this exact authenticated editor runtime."}}})
