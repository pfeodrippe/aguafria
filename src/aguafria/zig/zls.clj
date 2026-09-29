(ns aguafria.zig.zls
  "Bounded JSON-RPC connection to ZLS. Hover contents are returned verbatim."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [aguafria.zig.toolchain :as toolchain])
  (:import [java.io BufferedInputStream]
           [java.lang ProcessBuilder$Redirect ProcessHandle]
           [java.util.concurrent Executors Callable TimeUnit]))

(defn- read-message [input]
  (let [header (StringBuilder.)]
    (loop []
      (let [b (.read input)]
        (when (= -1 b) (throw (ex-info "ZLS closed its output" {})))
        (.append header (char b))
        (when-not (str/ends-with? (str header) "\r\n\r\n") (recur))))
    (let [[_ length] (re-find #"(?i)Content-Length: ([0-9]+)" (str header))
          bytes (.readNBytes input (Integer/parseInt length))]
      (json/read-str (String. bytes "UTF-8") :key-fn keyword))))

(defn send! [{:keys [output]} message]
  (let [bytes (.getBytes (json/write-str (assoc message :jsonrpc "2.0")) "UTF-8")]
    (.write output (.getBytes (str "Content-Length: " (alength bytes) "\r\n\r\n") "UTF-8"))
    (.write output bytes)
    (.flush output)))

(defn request! [{:keys [input executor counter] :as client} method params]
  (locking client
    (let [id (swap! counter inc)]
      (send! client {:id id :method method :params params})
      (let [task (.submit executor
                          ^Callable
                          (fn []
                            (loop []
                              (let [message (read-message input)]
                                (cond
                                  (= id (:id message))
                                  (if-let [error (:error message)]
                                    (throw (ex-info "ZLS request failed" {:method method :error error}))
                                    (:result message))
                                  (and (:id message) (:method message))
                                  (do (send! client {:id (:id message) :result nil}) (recur))
                                  :else (recur))))))]
        (try (.get task 30 TimeUnit/SECONDS)
             (catch java.util.concurrent.TimeoutException error
               (.destroyForcibly (:process client))
               (throw (ex-info "ZLS request timed out" {:method method} error))))))))

(defn start!
  "Start a dedicated ZLS instance with Aguafria's pinned Zig compiler."
  ([] (start! "zls"))
  ([executable]
   (let [process (.start (doto (ProcessBuilder. ^java.util.List [executable])
                           (.redirectError ProcessBuilder$Redirect/INHERIT)))
         client {:process process :input (BufferedInputStream. (.getInputStream process))
                 :output (.getOutputStream process) :counter (atom 0)
                 :executor (Executors/newSingleThreadExecutor)}]
     (try
       (request! client "initialize"
                 {:processId (.pid (ProcessHandle/current))
                  :rootUri nil
                  :capabilities {:general {:positionEncodings ["utf-16"]}
                                 :textDocument {:hover {:contentFormat ["markdown"]}}}
                  :initializationOptions {:zig_exe_path (toolchain/executable)
                                          :enable_build_on_save false}})
       (send! client {:method "initialized" :params {}})
       client
       (catch Throwable error
         (.destroyForcibly process)
         (.shutdownNow (:executor client))
         (throw error))))))

(defn stop! [client]
  (try
    (request! client "shutdown" nil)
    (send! client {:method "exit"})
    (finally
      (.destroy (:process client))
      (.shutdownNow (:executor client)))))

(defn hover-report!
  "Analyze generated Zig, using exact emission spans to associate hover results.
  Never guesses types when ZLS returns no result. One connection handles many files."
  [client file {:keys [source mappings]}]
  (let [uri (str (.toURI (.getAbsoluteFile (io/file file))))
        position (fn [offset]
                   (let [prefix (subs source 0 offset)
                         last-newline (.lastIndexOf prefix "\n")]
                     {:line (count (filter #{\newline} prefix))
                      :character (- offset (inc last-newline))}))]
    (send! client {:method "textDocument/didOpen"
                   :params {:textDocument {:uri uri :languageId "zig" :version 1 :text source}}})
    (try
      (mapv (fn [mapping]
              (let [hover (request! client "textDocument/hover"
                                    {:textDocument {:uri uri}
                                     :position (position (:zig-start mapping))})
                    content (:contents hover)
                    content (if (map? content) (:value content) content)]
                (assoc mapping :basis :zls :status (if content :known :unresolved)
                       :hover content)))
            (distinct mappings))
      (finally
        (send! client {:method "textDocument/didClose" :params {:textDocument {:uri uri}}})))))
