(ns racing-game.replay-fixture
  "Generate the portable golden intent stream consumed by native replay tests."
  (:require [aguafria.zig :as az]
            [clojure.java.io :as io]
            [racing-game.build :as build]
            [racing-game.core :as core]
            [racing-game.protocol :as protocol]
            [racing-game.simulation :as simulation])
  (:import [java.nio ByteBuffer ByteOrder]))

(def magic (.getBytes "AGRPLY01" java.nio.charset.StandardCharsets/US_ASCII))

(def header-bytes 32)

(def entry-bytes 32)

(defn fixture-file
  []
  (io/file (build/project-root) "resources/replay/golden-r3.bin"))

(defn- encode
  [entries]
  (let [buffer (doto (ByteBuffer/allocate
                      (+ header-bytes (* entry-bytes (count entries))))
                 (.order ByteOrder/LITTLE_ENDIAN))]
    (.put buffer magic)
    (.putShort buffer (short simulation/replay-file-version))
    (.putShort buffer (short (count entries)))
    (.put buffer (byte protocol/observation-schema-version))
    (.put buffer (byte protocol/action-schema-version))
    (.putShort buffer (short 0))
    (.putLong buffer (unchecked-long protocol/model-fingerprint))
    (.putLong buffer (unchecked-long protocol/action-head-fingerprint))
    (doseq [entry entries]
      (.put buffer (byte (:racer_id entry)))
      (.put buffer (byte (:rank entry)))
      (.putShort buffer (short (:lap entry)))
      (.put buffer (byte (:item entry)))
      (.put buffer (byte (:action entry)))
      (.put buffer (byte (:target entry)))
      (.put buffer (byte (if (:urgent entry) 1 0)))
      (.putLong buffer (unchecked-long (:revision entry)))
      (.putLong buffer (unchecked-long (:install_tick entry)))
      (.putFloat buffer (float (:lane_target entry)))
      (.putFloat buffer (float (:target_speed entry))))
    (.array buffer)))

(defn generate!
  []
  (az/await!)
  (let [report
        (az/value
         (simulation/run-replay-parity! protocol/replay-golden-ticks))
        entries (core/capture-replay)
        output (fixture-file)]
    (when-not (and (:valid report)
                   (= protocol/replay-golden-intent-count (count entries))
                   (= protocol/replay-golden-fingerprint
                      (:original_fingerprint report))
                   (= (:original_fingerprint report)
                      (:replay_fingerprint report)))
      (throw (ex-info "Golden native replay changed"
                      {:report report :intent-count (count entries)})))
    (io/make-parents output)
    (with-open [stream (java.io.FileOutputStream. output)]
      (.write stream (encode entries)))
    {:file output
     :bytes (.length output)
     :intents (count entries)
     :fingerprint (:original_fingerprint report)}))

(defn -main
  [& _]
  (try
    (prn (generate!))
    (finally
      (simulation/shutdown!)
      (shutdown-agents))))
