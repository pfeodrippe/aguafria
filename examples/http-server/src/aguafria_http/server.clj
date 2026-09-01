(ns aguafria-http.server
  "A small native HTTP server whose ordinary Zig functions stay live in nREPL."
  (:refer-clojure :exclude [run!])
  (:require [aguafria.pkg]
            [aguafria.pkg.uuid :as uuid]
            [aguafria.keyword :as ak]
            [aguafria.std]
            [aguafria.std.Io.net :as net]
            [aguafria.std.Io.net.IpAddress :as ip-address]
            [aguafria.std.Io.net.Server :as net-server]
            [aguafria.std.Io.net.Stream :as net-stream]
            [aguafria.std.http.Server :as http-server]
            [aguafria.std.http.Server.Request :as http-request]
            [aguafria.std.process :as std-process]
            [aguafria.zig :as az]
            [aguafria.zig.host :as host]))

(az/defconst port :u16 8787)

(az/defvar running false)

(az/defvar requests-served :u64 0)

(az/defn response-body
  "This is ordinary application logic. Edit its string and evaluate this form."
  :-
  [:slice-const :u8]
  []
  "Hello from live Aguafria Zig!\n")

(az/defn serve-connection!
  {:zig/qualifiers "!"}
  :-
  :void
  [[stream net/Stream]
   [io aguafria.std/Io]]
  (ak/defer (net-stream/close (ak/& stream) io))
  (let [^{:var true :zig/type [:array 4096 :u8]} read-buffer ak/undefined
        ^{:var true :zig/type [:array 4096 :u8]} write-buffer ak/undefined
        ^:var reader (net-stream/reader stream io (ak/& read-buffer))
        ^:var writer (net-stream/writer stream io (ak/& write-buffer))
        ^:var server
        (http-server/init
         (ak/& (az/field reader :interface))
         (ak/& (az/field writer :interface)))
        ^:var request (ak/try (http-server/receiveHead (ak/& server)))
        request-id (uuid/v4-new io)
        request-id-text (uuid/urn-serialize request-id)]
    (ak/try
     (http-request/respond
      (ak/& request)
      (response-body)
      {:keep_alive false
       :extra_headers (ak/& [{:name "x-request-id"
                              :value (az/slice request-id-text 0)}])}))
    (set! requests-served (+ requests-served 1))))
#_ (slurp server-url)

(az/defn request-stop!
  "Ask the native accept loop to stop after its current connection."
  :-
  :void
  []
  (set! running false))

(az/defn- running?
  :-
  :bool
  []
  running)

(az/defn- request-count
  :-
  :u64
  []
  requests-served)

(az/defn main
  "Listen on loopback and call the current response-body for every request."
  {:zig/qualifiers "!"}
  :-
  :void
  [[process-init std-process/Init]]
  (let [io (az/field process-init :io)
        address (ak/try (ip-address/parseIp4 "127.0.0.1" port))
        ^:var server (ak/try
                      (ip-address/listen
                       (ak/& address) io {:reuse_address true}))]
    (ak/defer (net-server/deinit (ak/& server) io))
    (set! requests-served 0)
    (set! running true)
    (ak/defer (set! running false))
    (ak/while running
      (let [stream (ak/try (net-server/accept (ak/& server) io))]
        (ak/try (serve-connection! stream io))))))

(def server-url "http://127.0.0.1:8787/")

(defonce ^:private active-host (atom nil))

(declare status)

(defn- await-running!
  []
  (loop [attempt 0]
    (cond
      (running?) true
      (< attempt 500) (do (Thread/sleep 10) (recur (inc attempt)))
      :else (throw (ex-info "Native HTTP server did not start"
                            {:url server-url
                             :host (some-> @active-host host/info)})))))

(defn start!
  "Start the Zig server on a native thread in this JVM."
  []
  (if (some-> @active-host host/info :active?)
    (status)
    (do
      (az/await! 'aguafria-http.server)
      (reset! active-host
              (host/start! #'main [] {:argv0 "aguafria-http-server"}))
      (await-running!)
      (status))))

(defn stop!
  "Stop and join the native server without stopping nREPL."
  []
  (when-let [handle @active-host]
    (when (:active? (host/info handle))
      (request-stop!)
      ;; Wake the blocking native accept so it can observe `running = false`.
      (try (slurp server-url) (catch Throwable _))
      (host/await! handle))
    (reset! active-host nil))
  (status))

(defn status
  "Return inspectable server, compiler, and native-host state."
  []
  {:url server-url
   :running (running?)
   :requests (request-count)
   :host (some-> @active-host host/info)
   :compiler (:summary (az/stats))})

(comment

  ;; Start once, then keep this JVM and native listener alive.
  (start!)
  (slurp server-url)

  ;; Edit only the string inside `response-body`, evaluate that az/defn in
  ;; Calva/CIDER, and make another request. No server-aware code or restart is
  ;; necessary: the already-running Zig loop calls the new function body.
  (az/await! 'aguafria-http.server)
  (slurp server-url)

  (status)
  (stop!)

  ())
