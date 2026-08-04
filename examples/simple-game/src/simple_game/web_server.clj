(ns simple-game.web-server
  "Dependency-free local server for the generated WebAssembly game."
  (:require [clojure.java.io :as io]
            [simple-game.build :as build])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]))

(defn- content-type
  [path]
  (cond
    (.endsWith path ".html") "text/html; charset=utf-8"
    (.endsWith path ".js") "text/javascript; charset=utf-8"
    (.endsWith path ".wasm") "application/wasm"
    :else "application/octet-stream"))

(defn- send!
  [^HttpExchange exchange status bytes content-type]
  (doto (.getResponseHeaders exchange)
    (.set "Content-Type" content-type)
    (.set "Cache-Control" "no-store"))
  (.sendResponseHeaders exchange status (alength ^bytes bytes))
  (with-open [output (.getResponseBody exchange)]
    (.write output bytes)))

(defn handler
  [root]
  (let [root (.getCanonicalFile ^java.io.File root)
        root-path (.toPath root)]
    (reify HttpHandler
      (handle [_ exchange]
        (try
          (let [request-path (.getPath (.getRequestURI exchange))
                relative (if (= request-path "/") "index.html" (subs request-path 1))
                file (.getCanonicalFile (io/file root relative))]
            (if (and (.startsWith (.toPath file) root-path) (.isFile file))
              (send! exchange 200 (java.nio.file.Files/readAllBytes (.toPath file))
                     (content-type (.getName file)))
              (send! exchange 404 (.getBytes "Not found" "UTF-8")
                     "text/plain; charset=utf-8")))
          (catch Throwable error
            (send! exchange 500 (.getBytes (str error) "UTF-8")
                   "text/plain; charset=utf-8")))))))

(defn start!
  ([] (start! 8787))
  ([port]
   (let [root (:web-build-root (build/paths))
         server (HttpServer/create (InetSocketAddress. "127.0.0.1" port) 0)]
     (when-not (.isFile (io/file root "index.html"))
       (build/build-web!))
     (.createContext server "/" (handler root))
     (.setExecutor server nil)
     (.start server)
     {:server server
      :url (str "http://127.0.0.1:" port "/")
      :root (.getAbsolutePath ^java.io.File root)})))

(defn -main
  [& [port]]
  (let [{:keys [url]} (start! (if port (parse-long port) 8787))]
    (println "Serving the Aguafria WebAssembly game at" url)
    @(promise)))
