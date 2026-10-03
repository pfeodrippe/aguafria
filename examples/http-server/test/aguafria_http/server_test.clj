(ns aguafria-http.server-test
  (:require [aguafria-http.server :as server]
            [aguafria.zig :as az]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.walk :as walk])
  (:import [java.io PushbackReader]
           [java.lang ProcessHandle]
           [java.net HttpURLConnection URI]))

(defn- connection-handler-form []
  (with-open [reader (PushbackReader.
                      (io/reader (io/resource "aguafria_http/server.clj")))]
    (loop []
      (let [form (read {:eof nil} reader)]
        (cond
          (nil? form) (throw (ex-info "Connection handler was not found" {}))
          (and (seq? form) (= 'serve-connection! (second form))) form
          :else (recur))))))

(defn- publish! [form]
  (binding [*ns* (the-ns 'aguafria-http.server)]
    (eval form))
  (az/await! 'aguafria-http.server))

(defn- request! []
  (let [^HttpURLConnection connection (.openConnection (.toURL (URI. server/server-url)))]
    (.setConnectTimeout connection 5000)
    (.setReadTimeout connection 5000)
    (try
      {:status (.getResponseCode connection)
       :request-id (.getHeaderField connection "x-request-id")
       :body (with-open [input (.getInputStream connection)] (slurp input))}
      (finally (.disconnect connection)))))

(deftest real-http-listener-keeps-state-through-live-edits
  (let [original (connection-handler-form)
        original-body "Hello from live Aguafria Zig!\n"
        pid (.pid (ProcessHandle/current))]
    (server/stop!)
    (try
      (let [started (server/start!)
            host-id (get-in started [:host :id])
            first-response (request!)]
        (is (true? (:running started)))
        (is (= 200 (:status first-response)))
        (is (= original-body (:body first-response)))
        (is (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
                        (:request-id first-response)))
        (is (= 1 (:requests (server/status))))
        (doseq [[index body] (map-indexed vector ["Live edit one\n" "Live edit two\n"])]
          (publish! (walk/postwalk-replace {original-body body} original))
          (let [response (request!)
                status (server/status)]
            (is (= body (:body response)))
            (is (= host-id (get-in status [:host :id])))
            (is (true? (get-in status [:host :active?])))
            (is (= (+ 2 index) (:requests status)))
            (is (= pid (.pid (ProcessHandle/current))))))
        (publish! original)
        (is (= original-body (:body (request!))))
        (is (= 4 (:requests (server/status)))))
      (finally
        (publish! original)
        (server/stop!)))
    (is (false? (:running (server/status))))
    (is (nil? (:host (server/status))))))
