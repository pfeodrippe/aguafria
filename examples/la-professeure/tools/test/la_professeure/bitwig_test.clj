(ns la-professeure.bitwig-test
  (:require [clojure.test :refer [deftest is run-tests use-fixtures]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [la-professeure.bitwig :as bitwig])
  (:import [java.net Socket]
           [java.io DataInputStream OutputStreamWriter]))

(use-fixtures :each (fn [test] (bitwig/stop!) (try (test) (finally (bitwig/stop!)))))

(defn await-state [state]
  (loop [attempt 0]
    (cond (= state (:state @bitwig/status)) true
          (= 200 attempt) false
          :else (do (Thread/sleep 10) (recur (inc attempt))))))

(deftest framed-requests-and-reconnect
  (let [{:keys [port]} (bitwig/start!)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Enable" (bitwig/inspect!)))
    (dotimes [_ 2]
      (with-open [peer (Socket. "127.0.0.1" port)
                  input (DataInputStream. (.getInputStream peer))
                  output (OutputStreamWriter. (.getOutputStream peer) "UTF-8")]
        (.setSoTimeout peer 3000)
        (is (await-state :connected))
        (let [response (future (bitwig/request! {:op "status" :text "Français ∆M"}))
              payload (byte-array (.readInt input))]
          (.readFully input payload)
          (let [request (json/read-str (String. payload "US-ASCII") :key-fn keyword)]
            (is (= "Français ∆M" (:text request)))
            (.write output (str (json/write-str {:id (:id request) :project "QA"}) "\n"))
            (.flush output)
            (is (= "QA" (:project (deref response 3000 {})))))))
      (is (await-state :listening)))
    (is (empty? @bitwig/pending))))

(deftest malformed-client-does-not-stop-listener
  (let [{:keys [port]} (bitwig/start!)]
    (with-open [peer (Socket. "127.0.0.1" port)
                writer (OutputStreamWriter. (.getOutputStream peer) "UTF-8")]
      (is (await-state :connected))
      (.write writer "not JSON\n")
      (.flush writer)
      (is (await-state :listening)))
    (with-open [peer (Socket. "127.0.0.1" port)]
      (is (await-state :connected)))))

(deftest disconnect-releases-in-flight-request
  (let [{:keys [port]} (bitwig/start!)
        response (atom nil)]
    (with-open [peer (Socket. "127.0.0.1" port)
                input (DataInputStream. (.getInputStream peer))]
      (.setSoTimeout peer 3000)
      (is (await-state :connected))
      (reset! response (future (try (bitwig/inspect!)
                                   (catch clojure.lang.ExceptionInfo e (.getMessage e)))))
      (let [bytes (byte-array (.readInt input))] (.readFully input bytes)))
    (is (re-find #"disconnected" (deref @response 3000 "timed out")))
    (is (await-state :listening))
    (is (empty? @bitwig/pending))))

(deftest safe-controller-install
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "la-professeure-controller-test-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        target (io/file directory "LaProfesseure.control.js")]
    (bitwig/start!)
    (is (= (str target) (bitwig/install! directory)))
    (is (.startsWith (slurp target) (str "var LP_PORT = " (:port @bitwig/status))))
    (is (= (str target) (bitwig/install! directory)))
    (spit target "This belongs to somebody else.")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unrecognized" (bitwig/install! directory)))
    (is (= "This belongs to somebody else." (slurp target)))))

(deftest scene-filter-and-stable-names
  (let [old {:recording-id "hello" :voice "la voiture" :text "Avant" :scene "one"}
        now (assoc old :text "Après")
        manifest {:passages [now (assoc old :recording-id "other" :scene "two")]}
        previous {:passages [old]}]
    (with-redefs [bitwig/request! identity]
      (let [plan (bitwig/sync! "Recording QA" "one" manifest previous)]
        (is (= "Recording QA" (:project plan)))
        (is (= [{:key "hello" :name "[LP:hello] la voiture — Après"
                 :previousName "[LP:hello] la voiture — Avant"}]
               (:tracks plan))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no voiced passages"
                            (bitwig/sync! "Recording QA" "missing" manifest previous))))))

(defn -main [& _]
  (let [result (run-tests 'la-professeure.bitwig-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
