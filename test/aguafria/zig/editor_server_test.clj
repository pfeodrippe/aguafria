(ns aguafria.zig.editor-server-test
  (:require [aguafria.zig.editor-server :as editor-server]
            [clojure.test :refer [deftest is testing]]
            [nrepl.core :as client])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- delete-temporary-tree!
  [root]
  (doseq [file (reverse (file-seq root))]
    (.delete ^java.io.File file)))

(deftest one-runtime-owns-a-workspace
  (let [workspace (-> (Files/createTempDirectory
                       "aguafria-editor-server-"
                       (make-array FileAttribute 0))
                      .toFile)
        active (atom nil)]
    (try
      (reset! active (editor-server/start! workspace {:authentication? true}))
      (testing "discovery is published only by the workspace owner"
        (is (.isFile (java.io.File. workspace ".nrepl-port")))
        (is (.isFile (java.io.File. workspace ".aguafria/runtime.edn")))
        (is (.isFile (java.io.File. workspace ".aguafria/editor-token"))))
      (testing "a racing editor process cannot overwrite the live runtime"
        (let [error (try
                      (editor-server/start! workspace {:authentication? true})
                      nil
                      (catch clojure.lang.ExceptionInfo exception exception))]
          (is (= :zig-editor-workspace-owned
                 (:aguafria/phase (ex-data error))))
          (is (re-find #"already owns this workspace" (ex-message error)))))
      (editor-server/stop! @active)
      (reset! active nil)
      (testing "clean stop removes only the owner's discovery files"
        (is (not (.exists (java.io.File. workspace ".nrepl-port"))))
        (is (not (.exists (java.io.File. workspace ".aguafria/runtime.edn"))))
        (is (not (.exists (java.io.File. workspace ".aguafria/editor-token")))))
      (finally
        (when @active (editor-server/stop! @active))
        (delete-temporary-tree! workspace)))))

(deftest authenticated-runtime-can-stop-itself-after-editor-reload
  (let [workspace (-> (Files/createTempDirectory
                       "aguafria-editor-shutdown-"
                       (make-array FileAttribute 0))
                      .toFile)
        active (atom nil)]
    (try
      (reset! active (editor-server/start! workspace {:authentication? true}))
      (let [{:keys [server token handshake shutdown-requested]} @active]
        (with-open [connection (client/connect :port (:port server))]
          (let [nrepl (client/client connection 5000)
                response
                (first
                 (client/message
                  nrepl
                  {:op "aguafria/shutdown"
                   :id "shutdown"
                   :token token
                   :protocol-version 1
                   :runtime-id (:runtime-id handshake)}))]
            (is (= ":stopping" (get-in response [:aguafria/result :status])))
            (is (= (:runtime-id handshake)
                   (get-in response [:aguafria/result :runtime-id])))))
        (is (true? (deref shutdown-requested 2000 false)))
        (reset! active nil)
        (is (not (.exists (java.io.File. workspace ".nrepl-port"))))
        (is (not (.exists (java.io.File. workspace ".aguafria/runtime.edn"))))
        (is (not (.exists (java.io.File. workspace ".aguafria/editor-token")))))
      (finally
        (when @active (editor-server/stop! @active))
        (delete-temporary-tree! workspace)))))

(deftest token-authentication-is-opt-in-for-loopback-nrepl-clients
  (let [workspace (-> (Files/createTempDirectory
                       "aguafria-editor-no-auth-"
                       (make-array FileAttribute 0))
                      .toFile)
        active (atom nil)]
    (try
      (reset! active (editor-server/start! workspace))
      (let [{:keys [server handshake shutdown-requested]} @active]
        (is (false? (:authentication-required? handshake)))
        (is (not (.exists (java.io.File. workspace ".aguafria/editor-token"))))
        (with-open [connection (client/connect :port (:port server))]
          (let [nrepl (client/client connection 5000)
                status (first (client/message
                               nrepl
                               {:op "aguafria/status"
                                :id "status-without-token"
                                :protocol-version 1
                                :runtime-id (:runtime-id handshake)}))
                shutdown (first (client/message
                                 nrepl
                                 {:op "aguafria/shutdown"
                                  :id "shutdown-without-token"
                                  :protocol-version 1
                                  :runtime-id (:runtime-id handshake)}))]
            (is (= 1 (get-in status [:aguafria/result :protocol-version])))
            (is (= ":stopping" (get-in shutdown [:aguafria/result :status])))))
        (is (true? (deref shutdown-requested 2000 false)))
        (reset! active nil))
      (finally
        (when @active (editor-server/stop! @active))
        (delete-temporary-tree! workspace)))))
