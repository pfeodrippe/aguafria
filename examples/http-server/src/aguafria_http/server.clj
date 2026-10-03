(ns aguafria-http.server
  "A small native HTTP server whose ordinary Zig functions stay live in nREPL."
  (:require [aguafria.pkg.uuid :as uuid]
            [aguafria.keyword :as k]
            [aguafria.std :as std]
            [aguafria.std.Io.net :as net]
            [aguafria.std.Io.net.IpAddress :as ip-address]
            [aguafria.std.Io.net.Server :as net-server]
            [aguafria.std.Io.net.Stream :as net-stream]
            [aguafria.std.http.Server :as http-server]
            [aguafria.std.http.Server.Request :as http-request]
            [aguafria.std.process :as std-process]
            [aguafria.zig :as a]
            [aguafria.zig.host :as host]))

(a/defconst port :u16 8787)

(a/defvar running false)

(a/defvar requests-served :u64 0)

(a/defn serve-connection! :!void
  [[stream net/Stream]
   [io std/Io]]
  (k/defer (net-stream/close (k/& stream) io))
  (let [read-buffer (k/var k/undefined [:array 4096 :u8])
        write-buffer (k/var k/undefined [:array 4096 :u8])
        reader (k/var (net-stream/reader stream io (k/& read-buffer)))
        writer (k/var (net-stream/writer stream io (k/& write-buffer)))
        server (k/var (http-server/init (k/& (:interface reader))
                                        (k/& (:interface writer))))
        request (k/var (try (http-server/receiveHead (k/& server))))
        request-id (uuid/v4-new io)
        request-id-text (uuid/urn-serialize request-id)]
    (try
      (http-request/respond
       (k/& request)
       "Hello from live Aguafria Zig!\n"
       {:keep_alive false
        :extra_headers (k/& [{:name "x-request-id"
                              :value (a/slice request-id-text 0)}])}))
    (k/+= requests-served 1)))

(a/defn request-stop! :void
  "Ask the native accept loop to stop after its current connection."
  []
  (k/= running false))

(a/defn- running? :bool
  []
  running)

(a/defn- request-count :u64
  []
  requests-served)

(a/defn main :!void
  "Listen on loopback and call the current connection handler for every request."
  [[process-init std-process/Init]]
  (let [io (:io process-init)
        address (try (ip-address/parseIp4 "127.0.0.1" port))
        server (k/var (try (ip-address/listen
                            (k/& address) io {:reuse_address true})))]
    (k/defer (net-server/deinit (k/& server) io))
    (k/= requests-served 0)
    (k/= running true)
    (k/defer (k/= running false))
    (k/while running
      (let [stream (try (net-server/accept (k/& server) io))]
        (try (serve-connection! stream io))))))

(def server-url "http://127.0.0.1:8787/")

(defonce ^:private active-host (atom nil))

(declare status)

(defn- await-running!
  []
  (loop [attempt 0]
    (cond
      (a/value (running?)) true
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
      (a/await! 'aguafria-http.server)
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
   :running (a/value (running?))
   :requests (a/value (request-count))
   :host (some-> @active-host host/info)
   :compiler (:summary (a/stats))})

(comment

  ;; Start once, then keep this JVM and native listener alive.
  (start!)
  (slurp server-url)

  ;; Edit only the string inside `serve-connection!`, evaluate that a/defn in
  ;; Calva/CIDER, and make another request. No server-aware code or restart is
  ;; necessary: the already-running Zig loop calls the new function body.
  (a/await! 'aguafria-http.server)
  (slurp server-url)

  (status)
  (stop!))
