(ns la-professeure.tools.takes
  "Offline take editing/alignment and durable recovery. Never called by an audio callback."
  (:require [clojure.java.io :as io] [clojure.edn :as edn])
  (:import [java.nio ByteBuffer ByteOrder] [java.nio.file Files StandardCopyOption CopyOption]
           [java.io RandomAccessFile FileOutputStream]))

(defn atomic-edn! [path value]
  (io/make-parents path)
  (let [target (.toPath (.getCanonicalFile (io/file path)))
        temp (Files/createTempFile (.getParent target) ".state-" ".tmp"
                                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (with-open [out (FileOutputStream. (.toFile temp))]
        (.write out (.getBytes (pr-str value) "UTF-8")) (.sync (.getFD out)))
      (Files/move temp target (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
      (finally (Files/deleteIfExists temp)))))

(defn read-state [path fallback] (if (.isFile (io/file path)) (edn/read-string (slurp path)) fallback))

;; The editor owns this document on its control worker, never on an audio callback.
;; WAVs remain immutable: undo changes references, not recorded media or publication.
(defn valid-index? [index]
  (and (map? index)
       (every? (fn [[id entry]]
                 (and (string? id) (map? entry) (vector? (:history entry))
                      (every? #(and (#{:dry :wet} (:kind %)) (string? (:path %))) (:history entry))
                      (every? (fn [key] (or (nil? (get entry key))
                                           (some #(= (get entry key) (:path %)) (:history entry))))
                              [:selected :preferred :dry :wet :published]))) index)))

(defn validate-project! [project]
  (when-not (and (map? project) (= 1 (:schema-version project))
                 (string? (:project-id project)) (seq (:project-id project))
                 (integer? (:revision project)) (<= 0 (:revision project))
                 (valid-index? (:takes project))
                 (every? (fn [stack]
                           (and (vector? stack) (<= (count stack) 64)
                                (every? #(and (string? (:label %)) (valid-index? (:takes %))) stack)))
                         [(:undo project) (:redo project)]))
    (throw (ex-info "Invalid or unsupported studio project; no fallback was loaded" {:code :invalid-project})))
  project)

(defn new-project [index]
  (validate-project! {:schema-version 1 :project-id (str (java.util.UUID/randomUUID))
                      :revision 0 :takes index :undo [] :redo []}))

(defn load-project!
  "Migrate the legacy index explicitly into a versioned document. Retain the legacy file and all WAVs."
  [path legacy]
  (if (.isFile (io/file path))
    (validate-project! (read-state path nil))
    (let [project (new-project (read-state legacy {}))]
      (atomic-edn! path project) project)))

(defn commit-project!
  "Persist before exposing the next revision. Barrier edits (publication) cannot be undone."
  [state path label change barrier?]
  (locking state
    (let [before (validate-project! @state) after (change (:takes before))]
      (if (and (= after (:takes before)) (not (and barrier? (or (seq (:undo before)) (seq (:redo before)))))) before
        (let [next (validate-project!
                    (assoc before :takes after :revision (inc (:revision before)) :redo []
                           :undo (if barrier? []
                                   (vec (take-last 64 (conj (:undo before) {:label label :takes (:takes before)}))))))]
          (atomic-edn! path next)
          (reset! state next))))))

(defn history-project! [state path direction]
  (when-not (#{:undo :redo} direction) (throw (ex-info "Invalid history direction" {:code :invalid-argument})))
  (locking state
    (let [before (validate-project! @state) stack (get before direction)
          entry (peek stack) opposite (if (= direction :undo) :redo :undo)]
      (when-not entry (throw (ex-info "No edit to undo/redo" {:code :empty-history})))
      (let [next (validate-project!
                  (-> before
                      (assoc :takes (:takes entry) :revision (inc (:revision before)) direction (pop stack))
                      (update opposite #(vec (take-last 64 (conj % {:label (:label entry) :takes (:takes before)}))))))]
        (atomic-edn! path next)
        (reset! state next)))))

(defn pcm [path]
  (with-open [f (RandomAccessFile. (io/file path) "r")]
    (let [header (byte-array 12)]
      (.readFully f header)
      (when-not (and (= "RIFF" (String. header 0 4 "US-ASCII")) (= "WAVE" (String. header 8 4 "US-ASCII")))
        (throw (ex-info "Expected WAV" {:path path}))))
    (loop [format-ok? false]
      (let [header (byte-array 8) _ (.readFully f header)
            kind (String. header 0 4 "US-ASCII")
            n (Integer/toUnsignedLong (.getInt (doto (ByteBuffer/wrap header) (.order ByteOrder/LITTLE_ENDIAN)) 4))]
        (when (or (> n 23040000) (> (+ (.getFilePointer f) n) (.length f)))
          (throw (ex-info "Invalid/bounded WAV chunk" {:path path})))
        (cond
          (= kind "fmt ")
          (let [data (byte-array n) _ (.readFully f data)
                b (doto (ByteBuffer/wrap data) (.order ByteOrder/LITTLE_ENDIAN))]
            (when (< n 16) (throw (ex-info "Invalid WAV format" {})))
            (when (odd? n) (.readByte f))
            (recur (and (= 3 (.getShort b 0)) (= 2 (.getShort b 2))
                        (= 48000 (.getInt b 4)) (= 32 (.getShort b 14)))))
          (= kind "data")
          (do (when-not (and format-ok? (zero? (mod n 8))) (throw (ex-info "Expected stereo f32/48k WAV" {})))
              (let [data (byte-array n)] (.readFully f data) data))
          :else (do (.seek f (+ (.getFilePointer f) n (mod n 2))) (recur format-ok?)))))))

(defn write-wav! [destination ^bytes data]
  (when (or (zero? (alength data)) (> (alength data) 23040000) (not (zero? (mod (alength data) 8))))
    (throw (ex-info "Invalid PCM frame count" {})))
  (io/make-parents destination)
  (when-not (.createNewFile (io/file destination)) (throw (ex-info "Refusing to overwrite take" {:path destination})))
  (let [b (doto (ByteBuffer/allocate 44) (.order ByteOrder/LITTLE_ENDIAN))]
    (.put b (.getBytes "RIFF" "US-ASCII")) (.putInt b (+ 36 (alength data)))
    (.put b (.getBytes "WAVEfmt " "US-ASCII")) (.putInt b 16)
    (.putShort b (short 3)) (.putShort b (short 2)) (.putInt b 48000) (.putInt b 384000)
    (.putShort b (short 8)) (.putShort b (short 32))
    (.put b (.getBytes "data" "US-ASCII")) (.putInt b (alength data))
    (with-open [out (FileOutputStream. (io/file destination))]
      (.write out (.array b)) (.write out data) (.sync (.getFD out))))
  (.getCanonicalPath (io/file destination)))

(defn trim! [source destination from-frame to-frame]
  (let [data (pcm source) frames (quot (alength ^bytes data) 8)]
    (when-not (<= 0 from-frame (dec to-frame) (dec frames))
      (throw (ex-info "Trim must keep at least one frame within the take" {:frames frames})))
    (write-wav! destination (java.util.Arrays/copyOfRange ^bytes data (int (* from-frame 8)) (int (* to-frame 8))))))

(defn waveform [path]
  (let [data (pcm path) b (doto (ByteBuffer/wrap data) (.order ByteOrder/LITTLE_ENDIAN))
        frames (quot (alength ^bytes data) 8)]
    {:frames frames
     :bins (mapv (fn [i]
                   (reduce max 0.0
                     (for [f (range (quot (* frames i) 128) (quot (* frames (inc i)) 128))
                           c [0 4]
                           :let [v (Math/abs (double (.getFloat b (+ (* f 8) c))))]]
                       (if (Double/isFinite v) (min 1.0 v) 1.0)))) (range 128))}))

(defn- fft! [^doubles re ^doubles im inverse?]
  (let [n (alength re)]
    (loop [i 1 j 0]
      (when (< i n)
        (let [j (loop [j j bit (bit-shift-right n 1)]
                  (if (pos? (bit-and j bit)) (recur (bit-xor j bit) (bit-shift-right bit 1)) (bit-xor j bit)))]
          (when (< i j)
            (let [r (aget re i) v (aget im i)]
              (aset re i (aget re j)) (aset im i (aget im j)) (aset re j r) (aset im j v)))
          (recur (inc i) j))))
    (loop [size 2]
      (when (<= size n)
        (let [angle (/ (* (if inverse? 2.0 -2.0) Math/PI) size)
              wr (Math/cos angle) wi (Math/sin angle) half (quot size 2)]
          (doseq [base (range 0 n size)]
            (loop [j 0 r 1.0 v 0.0]
              (when (< j half)
                (let [a (+ base j) b (+ a half)
                      br (- (* (aget re b) r) (* (aget im b) v))
                      bi (+ (* (aget re b) v) (* (aget im b) r))
                      ar (aget re a) ai (aget im a)]
                  (aset re a (+ ar br)) (aset im a (+ ai bi))
                  (aset re b (- ar br)) (aset im b (- ai bi))
                  (recur (inc j) (- (* r wr) (* v wi)) (+ (* r wi) (* v wr)))))))
        (recur (* size 2)))))
    (when inverse? (dotimes [i n] (aset re i (/ (aget re i) n)) (aset im i (/ (aget im i) n))))))

(defn alignment
  "Estimate 0–1 s return delay; reject silence, weak/ambiguous matches. No file mutation."
  [dry-path wet-path]
  (let [dry (pcm dry-path) wet (pcm wet-path)
        db (doto (ByteBuffer/wrap dry) (.order ByteOrder/LITTLE_ENDIAN))
        wb (doto (ByteBuffer/wrap wet) (.order ByteOrder/LITTLE_ENDIAN))
        count (min 96000 (quot (alength ^bytes dry) 8) (quot (alength ^bytes wet) 8))
        wc (min (+ count 48000) (quot (alength ^bytes wet) 8))
        size (loop [n 1] (if (>= n (+ count wc)) n (recur (* n 2))))
        dr (double-array size) di (double-array size) wr (double-array size) wi (double-array size)]
    (doseq [[buffer dest length] [[db dr count] [wb wr wc]]]
      (dotimes [i length]
        (let [v (double (.getFloat ^ByteBuffer buffer (* i 8)))]
          (when-not (Double/isFinite v) (throw (ex-info "Alignment refused: non-finite audio" {})))
          (aset ^doubles dest i v))))
    (let [energy (reduce + (map #(* % %) (take count dr)))]
      (when (< energy 1.0e-7) (throw (ex-info "Alignment refused: silent source" {}))))
    (fft! dr di false) (fft! wr wi false)
    (dotimes [i size]
      (let [r (+ (* (aget wr i) (aget dr i)) (* (aget wi i) (aget di i)))
            v (- (* (aget wi i) (aget dr i)) (* (aget wr i) (aget di i)))]
        (aset wr i r) (aset wi i v)))
    (fft! wr wi true)
    (let [limit (min 48000 (max 0 (- wc count)))
          lag (reduce (fn [a b] (if (> (Math/abs (aget wr b)) (Math/abs (aget wr a))) b a)) 0 (range (inc limit)))
          second (reduce max 0.0 (for [i (range (inc limit)) :when (> (Math/abs (long (- i lag))) 240)]
                                   (Math/abs (aget wr i))))
          peak (Math/abs (aget wr lag))
          [xx yy xy] (reduce (fn [[xx yy xy] i]
                              (let [x (double (.getFloat db (* i 8))) y (double (.getFloat wb (* (+ i lag) 8)))]
                                [(+ xx (* x x)) (+ yy (* y y)) (+ xy (* x y))])) [0.0 0.0 0.0] (range count))
          confidence (if (pos? (* xx yy)) (/ (Math/abs xy) (Math/sqrt (* xx yy))) 0.0)
          distinct (if (pos? peak) (- 1.0 (/ second peak)) 0.0)]
      {:frames lag :milliseconds (/ lag 48.0) :confidence confidence :distinctness distinct
       :accepted? (and (>= count 4800) (>= confidence 0.8) (> distinct 0.02))})))

(defn- recovered-wav! [directory kind ^bytes data]
  ;; A failed project save may leave the immutable WAV behind. Reuse only the
  ;; exact durable PCM, never overwrite a take or trust a filename alone.
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256") data)
        fingerprint (.formatHex (java.util.HexFormat/of) digest)
        target (io/file directory (str "recovered-" (name kind) "-" fingerprint ".wav"))]
    (when (Files/isSymbolicLink (.toPath target))
      (throw (ex-info "Recovery destination must not be a symbolic link" {:path target})))
    (if (.exists target)
      (do
        (when-not (and (.isFile target)
                       (java.util.Arrays/equals data ^bytes (pcm target)))
          (throw (ex-info "Recovery destination does not match retained PCM" {:path target})))
        (.getCanonicalPath target))
      (write-wav! target data))))

(defn recover-journal! [manifest]
  (let [{:keys [id completed? streams]} (read-state manifest nil)]
    (when-not (and (string? id) (re-matches #"[A-Za-z0-9_-]+" id))
      (throw (ex-info "Invalid recovery passage" {})))
    (when-not completed?
      (let [dir (.getCanonicalFile (.getParentFile (io/file manifest)))
            recovered
            (vec (for [[kind {:keys [file frames]}] streams :when (pos? frames)]
                   (let [original (io/file dir file) raw (.getCanonicalFile original)]
                     (when-not (and (#{:dry :wet} kind) (= dir (.getParentFile raw))
                                    (not (Files/isSymbolicLink (.toPath original)))
                                    (<= 1 frames 2880000) (>= (.length raw) (* frames 8)))
                       (throw (ex-info "Invalid recovery file/bounds" {:file file})))
                     (let [data (byte-array (* frames 8))]
                       (with-open [in (RandomAccessFile. raw "r")] (.readFully in data))
                       {:id id :kind kind :path (recovered-wav! dir kind data)}))))]
        recovered))))

(defn resolve-device [names wanted]
  (let [found (keep-indexed #(when (= wanted %2) %1) names)]
    (when-not (= 1 (count found)) (throw (ex-info "Missing or ambiguous audio device; select it explicitly" {:device wanted})))
    (first found)))
