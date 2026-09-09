(ns la-professeure.dialogue
  "Interactive Markdown and recording identity. No dependency on the renderer or DAW."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [aguafria.zig :as az]
            [la-professeure.recording-tool :as native])
  (:import [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]))

(def speakers {"V" "la voiture" "M" "la mangue"})

(defonce ^:private native-lock (Object.))

(declare digest)

(defn- native-value [value]
  (try (az/value value) (finally (az/close! value))))

(defn- native-string [value]
  (String. (byte-array (map unchecked-byte (native-value value))) "UTF-8"))

(defn parse-native
  "Production reader: native Aguafria Zig does tokenization and branch construction.
  This wrapper only copies its results to JVM values for the Bitwig adapter."
  [markdown]
  (locking native-lock
    (when-not (native/parse! markdown)
      (let [line (az/value native/error-line) code (az/value native/error-code)]
        (throw (ex-info (str "Dialogue line " line ": "
                            ({1 "Invalid heading, tag or choice" 2 "Dialogue capacity exceeded"
                              3 "Unknown speaker" 4 "Invalid indentation" 5 "Invalid/duplicate passage ID"
                              6 "Could not read or write the dialogue file"
                              7 "Duplicate scene heading; give repeated headings explicit IDs"} code))
                        {:line line :code code}))))
    (let [raw (mapv (fn [i]
                      (let [n (native-value (native/node-at i))
                            text (native-string (native/node-text i))
                            id (when (pos? (:id_len n)) (native-string (native/node-id i)))]
                        (assoc n :text text :id id)))
                    (range (az/value native/count-nodes)))
          keys (mapv #(if (zero? (:kind %)) (or (:id %) (str "scene-" (subs (digest (:text %)) 0 12)))
                        (str "node-" %2)) raw (range))]
      {:version 1 :speakers speakers
       :nodes (mapv (fn [n key]
                      (let [speaker (when (pos? (:speaker n)) (str (char (:speaker n))))]
                        {:key key :kind ([:scene :choice :passage] (:kind n))
                         :parent (when-not (= 4294967295 (:parent n)) (keys (:parent n)))
                         :scene (keys (:scene n)) :line (:line n) :indent (:indent n)
                         :id (:id n) :speaker speaker :voice (speakers speaker) :text (:text n)})) raw keys)})))

(defn- fail! [line message]
  (throw (ex-info (str "Dialogue line " line ": " message) {:line line})))

(defn digest [text]
  (apply str (map #(format "%02x" (bit-and 255 %))
                 (.digest (MessageDigest/getInstance "SHA-256")
                          (.getBytes (str text) "UTF-8")))))

(defn- line-token [number line voices]
  (let [indent (count (re-find #"^ *" line))
        content (str/trim line)
        [_ body block-id] (or (re-matches #"(.*?)\s+\[id:([A-Za-z0-9_-]+)\]" content)
                             (re-matches #"(.*?)\s+\^([A-Za-z0-9_-]+)" content)
                             [nil content nil])
        [_ heading] (re-matches #"#{1,6}\s+(.+)" body)
        [_ choice] (re-matches #"::\s+(.+)" body)
        [_ speaker speech] (re-matches #"#[∆Δ]?([A-Za-z][A-Za-z0-9_-]*)\s+(.+)" body)]
    (when (and speaker (not (contains? voices speaker)))
      (fail! number (str "Unknown speaker #∆" speaker "; add it to the speaker map.")))
    (when (and (str/starts-with? body "::") (not choice))
      (fail! number "A choice needs text after ::."))
    (when (and (str/starts-with? body "#") (not heading) (not speaker))
      (fail! number "Expected a scene heading or a speaker tag followed by text."))
    {:line number :indent indent :id block-id
     :kind (cond (empty? body) :blank heading :scene choice :choice :else :passage)
     :speaker speaker :voice (get voices speaker)
     :text (or heading choice speech body)}))

(defn parse
  "Parse scenes and nested :: branches. Returns flat nodes with explicit parent IDs.
  Indentation attaches a response to the preceding choice. Blank lines split passages."
  ([markdown] (parse markdown speakers))
  ([markdown voices]
   (let [tokens (map-indexed #(line-token (inc %1) %2 voices)
                             (str/split-lines (str/replace markdown "\t" "    ")))
         result
         (reduce
          (fn [{:keys [nodes scene stack join?] :as state} token]
            (let [{:keys [kind indent line text speaker]} token]
              (case kind
                :blank (assoc state :join? false)
                :scene (let [key (or (:id token) (str "scene-" (subs (digest text) 0 12)))]
                         (when (some #(= key (:key %)) nodes) (fail! line "Duplicate scene heading/id."))
                         {:nodes (conj nodes (assoc token :key key :scene key :parent nil))
                          :scene key :stack [] :join? false})
                (do
                  (when-not scene (fail! line "Start the document with a Markdown scene heading."))
                  (let [parents (vec (take-while #(< (:indent %) indent) stack))
                        parent (or (:key (peek parents)) scene)
                        previous (peek nodes)
                        continuation? (and join? (= kind :passage) (nil? speaker) (nil? (:id token))
                                           (= :passage (:kind previous)) (= parent (:parent previous))
                                           (= indent (:indent previous)))]
                    (when (and (pos? indent) (empty? parents))
                      (fail! line "Indented response has no enclosing :: choice."))
                    (if continuation?
                      (assoc state :nodes (conj (pop nodes) (update previous :text str " " text)))
                      (let [key (str "node-" (count nodes))
                            node (assoc token :key key :scene scene :parent parent)]
                        {:nodes (conj nodes node) :scene scene :join? (= kind :passage)
                         :stack (if (= kind :choice) (conj parents node) parents)})))))))
          {:nodes [] :stack [] :join? false} tokens)
         nodes (:nodes result)
         explicit (keep :id nodes)]
     (when (empty? nodes) (fail! 1 "The document contains no scenes."))
     (when-not (= (count explicit) (count (set explicit))) (fail! 1 "Duplicate explicit block ID."))
     {:version 1 :nodes nodes :speakers voices})))

(defn view
  "A scene or selected choice shows its direct passages and available choices."
  [document parent]
  (let [children (filterv #(= parent (:parent %)) (:nodes document))]
    {:passages (filterv #(= :passage (:kind %)) children)
     :choices (filterv #(= :choice (:kind %)) children)}))

(defn- context [nodes node]
  (let [index (into {} (map (juxt :key identity) nodes))]
    (loop [key (:parent node) result []]
      (if-let [parent (get index key)]
        (recur (:parent parent) (into [(:text parent)] result)) result))))

(defn reconcile
  "Assign stable recording IDs. Exact text survives moves; a unique edit in the same
  branch retains identity. Ambiguous rewrites require explicit ^block-ids. Never delete takes."
  [previous document]
  (let [old (vec (concat (:passages previous) (:archived previous)))
        nodes (:nodes document)
        fresh (mapv #(assoc % :context (context nodes %) :text-hash (digest (:text %)))
                    (filter :speaker nodes))
        used (atom #{})
        claim (fn [node candidates]
                (when (> (count candidates) 1)
                  (fail! (:line node) "Ambiguous recording identity; add an explicit ^block-id."))
                (when-let [match (first candidates)]
                  (swap! used conj (:recording-id match)) match))
        matches (mapv (fn [node]
                        (let [candidates (filter #(and (not (@used (:recording-id %)))
                                                       (if (:id node)
                                                         (= (:id node) (:id %))
                                                         (and (= (:speaker node) (:speaker %))
                                                              (= (:text-hash node) (:text-hash %))))) old)]
                          [node (claim node candidates)])) fresh)
        missing (filterv #(nil? (second %)) matches)
        records
        (mapv
         (fn [[node exact]]
           (let [same-place? #(and (= (:scene node) (:scene %))
                                  (= (:speaker node) (:speaker %))
                                  (= (:context node) (:context %)))
                 candidates (when-not (or exact (:id node))
                              (filter #(and (not (@used (:recording-id %))) (same-place? %)) old))
                 _ (when (and (seq candidates)
                              (> (count (filter #(same-place? (first %)) missing)) 1))
                     (fail! (:line node) "Multiple edited passages in one branch; use ^block-ids."))
                 prior (or exact (claim node candidates))
                 id (or (:recording-id prior) (:id node)
                        (str "voice-" (subs (digest (pr-str [(:scene node) (:context node)
                                                           (:speaker node) (:text node)])) 0 16)))
                 changed? (and prior (or (not= (:text-hash prior) (:text-hash node))
                                        (not= (:speaker prior) (:speaker node))))]
             (merge node {:recording-id id
                          :revision (if changed? (inc (:revision prior 1)) (:revision prior 1))
                          :status (if changed? :needs-review (:status prior :needs-recording))
                          :export (str "voices/" id ".wav")}))) matches)
        ids (mapv :recording-id records)]
    (when-not (= (count ids) (count (set ids)))
      (fail! 1 "Identical passages need distinct ^block-ids for independent recordings."))
    {:version 1 :passages records
     :archived (vec (remove #(contains? (set ids) (:recording-id %))
                           (vals (into {} (map (juxt :recording-id identity)
                                              (concat (:archived previous) old))))))}))

(defn atomic-write! [file text]
  (let [target (.toPath (.getAbsoluteFile (io/file file)))
        _ (io/make-parents (.toFile target))
        temporary (Files/createTempFile (.getParent target) ".dialogue-" ".tmp"
                                        (make-array java.nio.file.attribute.FileAttribute 0))]
    (spit (.toFile temporary) text :encoding "UTF-8")
    (Files/move temporary target (into-array StandardCopyOption
                                             [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
    file))

(defn prepare!
  "Read Markdown, reconcile with a sidecar, write actor cue sheets. Does not touch the source or audio."
  [source directory]
  (let [file (io/file directory "recordings.edn")
        document (parse-native (slurp source :encoding "UTF-8"))
        previous (when (.isFile file) (edn/read-string (slurp file)))
        manifest (reconcile previous document)]
    (atomic-write! (io/file directory "cues.md")
                   (str "# Recording cues\n\n"
                        (str/join "\n\n"
                                  (for [p (:passages manifest)]
                                    (str "## " (:voice p) " — " (:recording-id p) "\n\n"
                                         (str/join " → " (:context p)) "\n\n"
                                         (:text p) "\n\nRevision " (:revision p) " · " (name (:status p))
                                         " · Export: `" (:export p) "`")))))
    (atomic-write! file (pr-str manifest))
    {:document document :manifest manifest}))

(defn compile!
  "Reconcile recording IDs, then publish the native asset for the game."
  [source directory output]
  (locking native-lock
    (let [{:keys [document manifest] :as result} (prepare! source directory)
          indices (into {} (map-indexed (fn [i node] [(:key node) i]) (:nodes document)))]
      (doseq [p (:passages manifest)]
        (when-not (native/set-recording-id! (indices (:key p)) (:recording-id p) (:revision p))
          (throw (ex-info "Invalid recording ID for native asset" {:id (:recording-id p)}))))
      (io/make-parents (io/file output))
      (when-not (native/write-document! (str output))
        (throw (ex-info "Could not publish native dialogue asset" {:output (str output)})))
      result)))
