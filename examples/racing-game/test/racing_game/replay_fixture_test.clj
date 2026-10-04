(ns racing-game.replay-fixture-test
  (:require [aguafria.zig :as a]
            [clojure.test :refer [deftest is]]
            [racing-game.protocol :as protocol]
            [racing-game.replay-fixture :as fixture]
            [racing-game.simulation :as simulation])
  (:import [java.lang.foreign Arena]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- load-replay [file]
  (with-open [arena (Arena/ofConfined)
              native (simulation/load-replay-file! (.allocateFrom arena (str file)))]
    (a/value native)))

(deftest canonical-fixture-loads-with-strict-size-version-and-roster-checks
  (a/await!)
  (let [file (fixture/fixture-file)
        bytes (Files/readAllBytes (.toPath file))
        header (doto (ByteBuffer/wrap bytes) (.order ByteOrder/LITTLE_ENDIAN))
        temporaries (atom [])]
    (try
      (is (= 2 (.getShort header 8)))
      (is (= 599 (.getShort header 10)))
      (is (= 20 (bit-and 255 (aget bytes 14))))
      (is (= 10 (bit-and 255 (aget bytes 15))))
      (is (= 19200 (alength bytes)))
      (let [loaded (load-replay file)]
        (is (:valid loaded))
        (is (= (a/value simulation/replay-file-ok) (:error_code loaded)))
        (is (= (a/value protocol/replay-golden-intent-count) (:intent_count loaded)))
        (is (= (a/value protocol/model-fingerprint) (:model_fingerprint loaded)))
        (is (= (a/value protocol/action-head-fingerprint)
               (:action_head_fingerprint loaded))))
      (doseq [[label payload expected-error]
              [["truncated" (java.util.Arrays/copyOf bytes 31)
                simulation/replay-file-invalid-size]
               ["old-version" (doto (aclone bytes) (aset-byte 8 (byte 1)))
                simulation/replay-file-invalid-header]
               ["old-roster" (doto (aclone bytes) (aset-byte 14 (byte 0)))
                simulation/replay-file-incompatible]
               ["wrong-schema" (doto (aclone bytes) (aset-byte 12 (byte 5)))
                simulation/replay-file-incompatible]]]
        (let [path (Files/createTempFile (str "aguafria-replay-" label "-") ".bin"
                                         (make-array FileAttribute 0))]
          (swap! temporaries conj path)
          (Files/write path payload (make-array OpenOption 0))
          (let [loaded (load-replay (.toFile path))]
            (is (false? (:valid loaded)) label)
            (is (= (a/value expected-error) (:error_code loaded)) label))))
      (finally
        (doseq [path @temporaries]
          (Files/deleteIfExists path))
        (simulation/clear-replay!)
        (simulation/shutdown!)))))
