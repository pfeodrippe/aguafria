(ns la-professeure.takes-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [la-professeure.tools.takes :as takes])
  (:import [java.nio ByteBuffer ByteOrder] [java.nio.file Files]))

(defn directory [] (str (Files/createTempDirectory "professeure-takes-" (make-array java.nio.file.attribute.FileAttribute 0))))
(defn samples [values]
  (let [b (doto (ByteBuffer/allocate (* 8 (count values))) (.order ByteOrder/LITTLE_ENDIAN))]
    (doseq [v values] (.putFloat b (float v)) (.putFloat b (float (* 0.7 v)))) (.array b)))

(deftest durable-project-history
  (let [dir (directory) legacy (io/file dir "takes.edn") path (io/file dir "project.edn")
        wav (takes/write-wav! (io/file dir "original.wav") (samples [0.1 0.2 0.3]))
        index {"voice-one" {:selected wav :dry wav :history [{:kind :dry :path wav}]}}
        _ (takes/atomic-edn! legacy index)
        state (atom (takes/load-project! path legacy))
        rename #(assoc-in % ["voice-one" :history 0 :name] "Été")]
    (is (= index (:takes @state))) (is (= index (takes/read-state legacy nil)))
    (is (= 0 (:revision @state)))
    (takes/commit-project! state path "Nommer" rename false)
    (is (= 1 (:revision @state)))
    (is (= @state (takes/load-project! path legacy)))
    (takes/history-project! state path :undo)
    (is (= index (:takes @state))) (is (= 2 (:revision @state)))
    (takes/history-project! state path :redo)
    (is (= (rename index) (:takes @state))) (is (= 3 (:revision @state)))
    (is (= 24 (alength (takes/pcm wav))))
    (let [before @state disk (slurp path)]
      (with-redefs [takes/atomic-edn! (fn [& _] (throw (ex-info "disk full" {})))]
        (is (thrown? Exception (takes/history-project! state path :undo)))
        (is (thrown? Exception (takes/commit-project! state path "Preferred" #(assoc-in % ["voice-one" :preferred] wav) false))))
      (is (= before @state)) (is (= disk (slurp path))))
    (takes/history-project! state path :undo)
    (takes/commit-project! state path "Preferred" #(assoc-in % ["voice-one" :preferred] wav) false)
    (is (empty? (:redo @state)))
    (takes/commit-project! state path "Publish" #(assoc-in % ["voice-one" :published] wav) true)
    (is (empty? (:undo @state)))
    (is (thrown? Exception (takes/history-project! state path :undo)))
    (dotimes [i 80] (takes/commit-project! state path "Rename" #(assoc-in % ["voice-one" :history 0 :name] (str i)) false))
    (is (= 64 (count (:undo @state))))
    (let [before @state]
      (takes/commit-project! state path "No change" identity false)
      (is (= before @state)))
    (takes/atomic-edn! path {:schema-version 99})
    (is (thrown? Exception (takes/load-project! path legacy)))
    (is (= index (takes/read-state legacy nil)))
    (is (thrown? Exception (takes/new-project {"bad" {:selected "missing" :history []}})))))

(deftest latency-and-nondestructive-editing
  (let [dir (directory) random (java.util.Random. 42)
        signal (vec (repeatedly 12000 #(- (* 0.3 (.nextDouble random)) 0.15)))
        dry (takes/write-wav! (io/file dir "dry.wav") (samples signal))
        wet (takes/write-wav! (io/file dir "wet.wav") (samples (concat (repeat 731 0) (map #(* -0.5 %) signal) (repeat 1000 0))))
        result (takes/alignment dry wet)
        aligned (takes/trim! wet (io/file dir "aligned.wav") (:frames result) 12731)]
    (is (:accepted? result)) (is (= 731 (:frames result))) (is (> (:confidence result) 0.999))
    (is (= 96000 (alength (takes/pcm aligned))))
    (is (= (* 8 13731) (alength (takes/pcm wet))))
    (is (= 128 (count (:bins (takes/waveform wet)))))
    (is (thrown? Exception (takes/trim! wet aligned 0 100)))
    (is (thrown? Exception (takes/trim! wet (io/file dir "bad.wav") 8 8)))
    (is (thrown? Exception (takes/alignment (takes/write-wav! (io/file dir "silence.wav") (samples (repeat 6000 0))) wet)))
    (is (thrown? Exception (takes/alignment dry (takes/write-wav! (io/file dir "nan.wav") (samples (repeat 6000 Float/NaN))))))
    (is (false? (:accepted? (takes/alignment dry (takes/write-wav! (io/file dir "short.wav") (samples (take 100 signal)))))))
    (let [tone (map #(Math/sin (* % 0.1)) (range 24000))
          a (takes/write-wav! (io/file dir "tone.wav") (samples tone))
          b (takes/write-wav! (io/file dir "tone-return.wav") (samples (concat tone tone)))]
      (is (false? (:accepted? (takes/alignment a b)))))))

(deftest durable-prefix-recovery
  (let [dir (directory) raw (io/file dir "dry.pcm") manifest (io/file dir "session.edn")
        data (samples (repeat 100 0.05))]
    (with-open [out (io/output-stream raw)] (.write out data))
    ;; Simulate a process dying after PCM was appended but before its next manifest commit.
    (takes/atomic-edn! manifest {:id "voice-test" :completed? false :streams {:dry {:file "dry.pcm" :frames 63}}})
    (let [entry (first (takes/recover-journal! manifest))]
      (is (= "voice-test" (:id entry))) (is (= :dry (:kind entry)))
      (is (= 504 (alength (takes/pcm (:path entry))))) (is (= 800 (.length raw))))
    (takes/atomic-edn! manifest {:id "voice-test" :completed? true})
    (is (nil? (takes/recover-journal! manifest)))
    (takes/atomic-edn! manifest {:id "voice-test" :streams {:dry {:file "../escape.pcm" :frames 1}}})
    (is (thrown? Exception (takes/recover-journal! manifest)))))

(deftest routing-name-resolution
  (is (= 1 (takes/resolve-device ["Speakers" "Headphones"] "Headphones")))
  (is (thrown? Exception (takes/resolve-device ["Speakers"] "Headphones")))
  (is (thrown? Exception (takes/resolve-device ["Headphones" "Headphones"] "Headphones"))))
