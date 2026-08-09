(ns aguafria.zig.nrepl-test
  (:require [aguafria.zig.editor :as editor]
            [aguafria.zig.nrepl :as aguafria-nrepl]
            [clojure.test :refer [deftest is]]
            [nrepl.core :as client]
            [nrepl.server :as server]))

(deftest exposes-discoverable-authenticated-editor-operations
  (let [previous (System/getProperty "aguafria.editor.token")
        token "editor-test-token"]
    (System/setProperty "aguafria.editor.token" token)
    (with-open [server (server/start-server
                        :port 0
                        :handler (server/default-handler
                                  #'aguafria-nrepl/wrap-editor))
                connection (client/connect :port (:port server))]
      (let [nrepl (client/client connection 5000)
            described (first (client/message nrepl
                                             {:op "aguafria/describe"
                                              :id "describe"}))
            runtime-id (get-in described [:aguafria/result :runtime-id])
            rejected (first (client/message nrepl
                                            {:op "aguafria/status"
                                             :id "rejected"}))
            accepted (first (client/message nrepl
                                            {:op "aguafria/status"
                                             :id "accepted"
                                             :token token
                                             :protocol-version 1
                                             :runtime-id runtime-id}))
            exact
            (with-redefs [editor/inspect!
                          (fn [& _]
                            {:value 1208925819614629174706177N})]
              (first (client/message nrepl
                                     {:op "aguafria/value"
                                      :id "exact"
                                      :project-id "project"
                                      :uri "file:///value.zig"
                                      :source "const value = 0;"
                                      :position {:line 0 :character 7}
                                      :token token
                                      :protocol-version 1
                                      :runtime-id runtime-id})))]
        (is (= 1 (get-in described [:aguafria/result :protocol-version])))
        (is (some #{"error"} (:status rejected)))
        (is (string? (get-in rejected [:aguafria/error :message])))
        (is (= 1 (get-in accepted [:aguafria/result :protocol-version])))
        (is (= "1208925819614629174706177"
               (get-in exact [:aguafria/result :value :decimal])))
        (is (= "integer"
               (get-in exact [:aguafria/result :value :zig/value-kind])))))
    (if previous
      (System/setProperty "aguafria.editor.token" previous)
      (System/clearProperty "aguafria.editor.token"))
    (editor/clear!)))
