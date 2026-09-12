(ns pitoco.client
  "Optional external controller. No AguaFria, native compiler, or engine dependency."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.nio.channels FileChannel OverlappingFileLockException]
           [java.nio.file Files StandardCopyOption StandardOpenOption]))

(def operations
  {:seek 1 :pause 2 :play 3 :export 4 :stop 5
   :load-plugin 6 :unload-plugin 7 :plugin-command 8})

(def results
  {0 :ok 1 :queued 2 :busy 3 :invalid 4 :abi-mismatch
   5 :not-found 6 :plugin-error 7 :io-error})

(defn connect
  "Connect to an existing Pitoco bridge directory. Does not start a JVM in Pitoco."
  [directory]
  (let [directory (.getCanonicalFile (io/file directory))]
    (when-not (.isDirectory directory)
      (throw (ex-info "Pitoco bridge directory does not exist" {:directory (str directory)})))
    {:directory directory :timeout-ms 5000}))

(defn- line! [value]
  (let [text (str value)]
    (when (or (re-find #"[\r\n\x00]" text)
              (> (alength (.getBytes text "UTF-8")) 4096))
      (throw (ex-info "Command value must fit one 4096-byte line" {})))
    text))

(defn- exchange!
  [{:keys [directory timeout-ms]} operation integer text]
  (let [wire (str "PITOCO/1\n" (line! operation) "\n" (line! integer) "\n" (line! text) "\n")
        lock-path (.toPath (io/file directory ".client.lock"))
        request (io/file directory "request")
        reply (io/file directory "reply")
        temporary (io/file directory (str "request-" (random-uuid) ".tmp"))
        deadline (+ (System/nanoTime) (* 1000000 timeout-ms))]
    (with-open [channel (FileChannel/open lock-path
                                         (into-array StandardOpenOption
                                                     [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
      (let [lock (try (.tryLock channel) (catch OverlappingFileLockException _ nil))]
        (when-not lock (throw (ex-info "Another scripting client is using this bridge" {})))
        (with-open [_ lock]
          (when (or (.exists request) (.exists reply))
            (throw (ex-info "Unresolved bridge exchange; inspect request/reply before retrying" {})))
          (try
            (spit temporary wire :encoding "UTF-8")
            (Files/move (.toPath temporary) (.toPath request)
                        (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
            (loop []
              (cond
                (.exists reply)
                (let [response (edn/read-string (slurp reply :encoding "UTF-8"))]
                  (when-not (and (= 1 (:protocol response)) (contains? results (:result response)))
                    (throw (ex-info "Invalid Pitoco response" {:response response})))
                  (Files/delete (.toPath reply))
                  (update response :result results))

                (> (System/nanoTime) deadline)
                (throw (ex-info "Pitoco response timed out; execution is uncertain, do not blindly retry"
                                {:request (str request) :reply (str reply)}))

                :else (do (Thread/sleep 10) (recur))))
            (finally (Files/deleteIfExists (.toPath temporary)))))))))

(defn status [connection]
  (exchange! connection "status" 0 ""))

(defn result [connection ticket]
  (when-not (and (integer? ticket) (pos? ticket) (<= ticket Long/MAX_VALUE))
    (throw (ex-info "Expected a positive command ticket" {:ticket ticket})))
  (exchange! connection "result" ticket ""))

(defn command!
  "Submit data to the native command queue. :queued acknowledges admission only."
  [connection {:keys [op tick text] :or {tick 0 text ""}}]
  (when-not (and (operations op) (integer? tick) (<= 0 tick Long/MAX_VALUE))
    (throw (ex-info "Unknown command or invalid tick" {:op op :tick tick})))
  (exchange! connection (operations op) tick text))

(defn await!
  "Wait for native dispatch. Export completion is not implied by dispatch."
  [connection {:keys [result ticket] :as admission}]
  (if (not= :queued result)
    admission
    (let [deadline (+ (System/nanoTime) (* 1000000 (:timeout-ms connection)))]
      (loop []
        (let [completion (pitoco.client/result connection ticket)]
          (if (not= :queued (:result completion))
            completion
            (if (> (System/nanoTime) deadline)
              (throw (ex-info "Command still queued" {:ticket ticket}))
              (do (Thread/sleep 10) (recur)))))))))

(defn seek! [connection tick]
  (command! connection {:op :seek :tick tick}))

(defn load-plugin! [connection library]
  (command! connection {:op :load-plugin :text (.getCanonicalPath (io/file library))}))

(defn unload-plugin! [connection id]
  (command! connection {:op :unload-plugin :text id}))

(defn plugin-command! [connection id command]
  (when-not (and (string? id) (re-matches #"[a-z0-9_.-]{1,64}" id))
    (throw (ex-info "Invalid plugin ID" {:id id})))
  (command! connection {:op :plugin-command :text (str id "\t" command)}))
