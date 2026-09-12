(ns pitoco.client-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing run-tests]]
            [clojure.java.io :as io]
            [pitoco.client :as pitoco])
  (:import [java.nio.file Files]))

(defn dispatch! [connection command]
  (pitoco/await! connection (pitoco/command! connection command)))

(defn raw-exchange! [directory wire]
  (spit (io/file directory "request") wire)
  (loop [attempt 0]
    (let [reply (io/file directory "reply")]
      (if (.exists reply)
        (let [value (edn/read-string (slurp reply))]
          (.delete reply)
          value)
        (if (> attempt 500)
          (throw (ex-info "Malformed-request test timed out" {}))
          (do (Thread/sleep 10) (recur (inc attempt))))))))

(deftest separate-native-process
  (let [root (.getCanonicalFile (io/file ".."))
        directory (.toFile (Files/createTempDirectory "pitoco-client-test-"
                                                     (make-array java.nio.file.attribute.FileAttribute 0)))
        executable (str (io/file root "build/pitoco-host-test"))
        library (str (io/file root "plugins/rewind/build/libpitoco-rewind.dylib"))
        incompatible (str (io/file root "build/incompatible-plugin.dylib"))
        log (io/file root "extension-host-test-log.txt")
        process (-> (ProcessBuilder. ^java.util.List [executable library incompatible (str directory)])
                    (.redirectErrorStream true)
                    (.redirectOutput log)
                    (.start))
        connection (pitoco/connect directory)]
    (try
      (loop [attempt 0]
        (when-not (.exists (io/file directory "ready"))
          (when (or (> attempt 500) (not (.isAlive process)))
            (throw (ex-info "Native test host did not start" {:log (slurp log)})))
          (Thread/sleep 10)
          (recur (inc attempt))))
      (is (= 241 (:frames (pitoco/status connection))))
      (is (= :ok (:result (dispatch! connection {:op :seek :tick 121}))))
      (is (= 121 (:cursor (pitoco/status connection))))
      (is (= :ok (:result (dispatch! connection {:op :play}))))
      (is (false? (:paused? (pitoco/status connection))))
      (is (= :invalid (:result (dispatch! connection {:op :seek :tick 241}))))
      (is (thrown? clojure.lang.ExceptionInfo (pitoco/seek! connection -1)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (pitoco/command! connection {:op :load-plugin :text "bad\nrequest"})))
      (is (= :ok (:result (pitoco/await! connection (pitoco/load-plugin! connection library)))))
      (is (= 1 (:plugins (pitoco/status connection))))
      (is (= :ok (:result (pitoco/await! connection
                                        (pitoco/plugin-command! connection "example.rewind" "rewind")))))
      (Thread/sleep 30)
      (is (= 0 (:cursor (pitoco/status connection))))
      (is (= :ok (:result (pitoco/await! connection
                                        (pitoco/unload-plugin! connection "example.rewind")))))
      (is (= 0 (:plugins (pitoco/status connection))))
      (doseq [wire ["PITOCO/99\nstatus\n0\n\n"
                    "PITOCO/1\n1\n0\n\njunk"
                    "PITOCO/1\n1\n0\n\u0000\n"
                    "PITOCO/1\n1\n9999999999999999999999999\n\n"
                    "truncated"
                    (str "PITOCO/1\n8\n0\n" (apply str (repeat 9000 "x")) "\n")]]
        (is (= 3 (:result (raw-exchange! directory wire)))))
      (is (= 0 (:cursor (pitoco/status connection))))
      (is (nil? (find-ns 'aguafria.zig)))
      (finally
        (spit (io/file directory "stop") "stop")
        (when-not (.waitFor process 10 java.util.concurrent.TimeUnit/SECONDS)
          (.destroyForcibly process))
        (is (= 0 (.exitValue process)) (slurp log))
        (doseq [file (reverse (file-seq directory))] (.delete file))))))

(defn -main [& _]
  (let [result (run-tests 'pitoco.client-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
