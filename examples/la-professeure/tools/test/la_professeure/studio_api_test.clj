(ns la-professeure.studio-api-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [aguafria.zig :as az]
            [clojure.edn :as edn]
            [la-professeure.tools.recorder :as recorder]
            [la-professeure.tools.mixer :as mixer]
            [la-professeure.tools.takes :as files]
            [la-professeure.scene :as scene]
            [la-professeure.core :as core]
            [la-professeure.tools.studio :as studio]))

(deftest comparison-readiness-follows-real-takes-and-passage
  (let [directory (java.nio.file.Files/createTempDirectory
                    "studio-comparison-" (make-array java.nio.file.attribute.FileAttribute 0))
        a (str (.resolve directory "a.wav"))
        b (str (.resolve directory "b.wav"))
        missing (str (.resolve directory "missing.wav"))
        _ (doseq [path [a b]] (.createNewFile (io/file path)))
        takes {"passage" {:selected b :history [{:path a} {:path b} {:path missing}]}}
        reference {:id "passage" :path a :playing-a? false}
        state #(studio/comparison-state %1 %2 %3)]
    (is (= :unmarked (:state (state takes nil "passage"))))
    (is (= :unmarked (:state (state takes reference "another-passage"))))
    (is (= {:state :listen-a :ready? true :a a :b b}
           (state takes reference "passage")))
    (is (= :listen-b (:state (state takes (assoc reference :playing-a? true) "passage"))))
    (let [same (state (assoc-in takes ["passage" :selected] a) reference "passage")]
      (is (= :select-b (:state same)))
      (is (false? (:ready? same))))
    (let [lost-a (state takes (assoc reference :path missing) "passage")
          lost-b (state (assoc-in takes ["passage" :selected] missing) reference "passage")]
      (is (= :missing-a (:state lost-a)))
      (is (= :missing-b (:state lost-b)))
      (is (false? (:ready? lost-a)))
      (is (false? (:ready? lost-b))))
    (is (= :missing-a
           (:state (state (assoc-in takes ["passage" :history] [{:path b}])
                          reference "passage")))
        "A removed from the project is unavailable even if the file still exists")))

(deftest comparison-playback-does-not-lend-a-cursor-to-b
  (let [directory (java.nio.file.Files/createTempDirectory
                    "studio-comparison-playback-" (make-array java.nio.file.attribute.FileAttribute 0))
        a (str (.resolve directory "a.wav"))
        b (str (.resolve directory "b.wav"))
        _ (doseq [path [a b]] (.createNewFile (io/file path)))
        reference (atom {:id "passage" :path a :playing-a? false})
        played (atom [])
        fields [studio/preview-node studio/preview-paused studio/seek-seconds studio/audition-source-peak]
        before (mapv az/value fields)]
    (try
      (with-redefs-fn
        {#'studio/takes (atom {"passage" {:selected b :history [{:path a} {:path b}]}})
         #'studio/comparison reference
         #'studio/selected-entry (fn [] ["passage" {:path b}])
         #'studio/render! (fn [f] (f))
         #'studio/message! (fn [_])
         #'studio/refresh-comparison! (fn [_])
         #'files/waveform (fn [_] {:bins [0.1]})
         #'studio/play-voice-file! (fn [path] (swap! played conj path) true)}
        (fn []
          (#'studio/edit-take! :compare)
          (is (= [a] @played))
          (is (= 4294967295 (az/value studio/preview-node))
              "Reference A must not drive selected B's playhead or per-take pause")
          (#'studio/edit-take! :compare)
          (is (= [a b] @played))
          (is (= (az/value studio/selected) (az/value studio/preview-node)))
          (reset! reference {:id "passage" :path b :playing-a? false})
          (let [error (try (#'studio/edit-take! :compare) nil
                           (catch clojure.lang.ExceptionInfo e (ex-data e)))]
            (is (= {:code :comparison-not-ready :state :select-b} error)))
          (reset! reference nil)
          (let [error (try (#'studio/edit-take! :compare) nil
                           (catch clojure.lang.ExceptionInfo e (ex-data e)))]
            (is (= {:code :comparison-not-ready :state :unmarked} error)))
          (is (= [a b] @played) "Rejected comparisons never open audio")))
      (finally
        (doseq [[field value] (map vector fields before)]
          (az/set-value! field value))))))

(deftest frame-timing-summary-reports-distributions-not-just-average
  (let [empty (studio/summarize-frame-timings [])
        samples (mapv (fn [ms] {:interval-ms (double ms) :build-ms 2.0 :render-ms 3.0})
                      (range 1 101))
        result (studio/summarize-frame-timings samples)]
    (is (= 0 (:samples empty)))
    (is (nil? (:fps empty)))
    (is (nil? (:frame-interval-ms empty)))
    (is (= {:mean 50.5 :p95 95.0 :p99 99.0 :max 100.0} (:frame-interval-ms result)))
    (is (= (/ 1000.0 50.5) (:fps result)))
    (is (= 84 (:intervals-over-16.67-ms result)))
    (is (= 2.0 (get-in result [:ui-build-ms :p95])))
    (is (= 3.0 (get-in result [:render-call-ms :max])))))

(deftest dialogue-freshness-is-about-captured-content-not-position
  (let [original (studio/dialogue-fingerprint 86 "Bonjour.")
        edited (studio/dialogue-fingerprint 86 "Bonsoir.")
        different-speaker (studio/dialogue-fingerprint 77 "Bonjour.")
        recorded {:path "existing.wav" :dialogue-hash original}]
    (is (= :needs-recording (studio/recording-freshness original nil)))
    (is (= :unverified (studio/recording-freshness original {:path "legacy.wav"})))
    (is (= :recorded (studio/recording-freshness original recorded)))
    (is (= :changed (studio/recording-freshness edited recorded)))
    (is (= :changed (studio/recording-freshness different-speaker recorded)))
    (is (= :recorded (studio/recording-freshness
                      (studio/dialogue-fingerprint 86 "Bonjour.") recorded))
        "Moving/reverting text preserves the content match")
    (is (= :interrupted (studio/recording-freshness original (assoc recorded :interrupted? true))))
    (is (= {:path "existing.wav" :dialogue-hash original} recorded)
        "Checking freshness never rewrites old take provenance")))

(deftest remembered-takes-preserve-session-and-derived-provenance
  (let [state (atom {})
        session (atom {:id "voice" :dialogue-hash "captured-before-edit"})]
    (with-redefs-fn
      {#'studio/session session
       #'studio/change-takes! (fn [_ change] (swap! state change))}
      (fn []
        (#'studio/remember! "voice" :dry "dry.wav")
        (reset! session nil)
        (#'studio/remember! "voice" :wet "processed.wav"
                           {:dialogue-hash "captured-before-edit" :interrupted? true :path "ignored"})
        (#'studio/remember! "voice" :dry "imported.wav")
        (let [[dry wet imported] (get-in @state ["voice" :history])]
          (is (= "captured-before-edit" (:dialogue-hash dry)))
          (is (= "captured-before-edit" (:dialogue-hash wet)))
          (is (:interrupted? wet))
          (is (= "processed.wav" (:path wet)))
          (is (nil? (:dialogue-hash imported))))))))

(deftest dialogue-status-refresh-does-not-read-text-on-meter-ticks
  (let [reads (atom 0)
        source (atom {:dialogue {:state :reloaded :at 1}})
        take-state (atom {})
        display (atom [])
        fingerprint (studio/dialogue-fingerprint 86 "Bonjour.")]
    (with-redefs-fn
      {#'studio/dialogue-display display
       #'studio/dialogue-display-key (atom nil)
       #'studio/takes take-state
       #'core/status source
       #'studio/render! (fn [f] (f))
       #'az/value (fn [field] (if (identical? field scene/passage-entity-count) 1 0))
       #'studio/passage-data (fn [index]
                               (swap! reads inc)
                               {:index index :id "voice" :revision 1 :speaker 86 :text "Bonjour."})
       #'studio/set-passage-freshness! (fn [& _])}
      (fn []
        (#'studio/refresh-dialogue-status!)
        (#'studio/refresh-dialogue-status!)
        (is (= 1 @reads))
        (is (= :needs-recording (:recording-status (first @display))))
        (swap! source assoc-in [:dialogue :at] 2)
        (#'studio/refresh-dialogue-status!)
        (is (= 2 @reads) "A published script invalidates cached text")
        (reset! take-state {"voice" {:selected "take.wav"
                                    :history [{:path "take.wav" :dialogue-hash fingerprint}]}})
        (#'studio/refresh-dialogue-status!)
        (is (= 3 @reads))
        (is (= :recorded (:recording-status (first @display))))))))

(deftest input-check-lifecycle-is-explicit-and-bounded
  (let [check (atom nil)
        calls (atom [])]
    (with-redefs-fn
      {#'studio/input-check check
       #'studio/render! (fn [_] {:source 2 :fx? true})
       #'recorder/initialize! (constantly true)
       #'recorder/start-input-check! (fn [source fx?]
                                      (swap! calls conj [:start source fx?])
                                      true)
       #'recorder/stop-input-check! #(swap! calls conj [:stop])}
      (fn []
        (#'studio/check-input! true)
        (is (= [[:start 2 true]] @calls))
        (is (= {:source 2 :fx? true} (select-keys @check [:source :fx?])))
        (is (< 0 (- (:deadline-ns @check) (System/nanoTime)) 10000000001))
        (#'studio/check-input! true)
        (#'studio/expire-input-check!)
        (is (= 1 (count @calls)) "Repeated enable is idempotent; unexpired checks stay open")
        (swap! check assoc :deadline-ns 0)
        (#'studio/expire-input-check!)
        (is (nil? @check))
        (is (= [[:start 2 true] [:stop]] @calls))
        (#'studio/expire-input-check!)
        (is (= 2 (count @calls)))
        (#'studio/check-input! true)
        (#'studio/check-input! false)
        (is (nil? @check))
        (is (= [:stop] (last @calls)))))))

(deftest monitor-level-api-is-bounded-without-owning-recording
  (doseq [percent [-1 51 100 0.5 "15" nil]]
    (is (thrown? clojure.lang.ExceptionInfo
          (#'studio/validate-command! {:op :routing/monitor-level :args {:percent percent}}))))
  (let [levels (atom [])
        capture {:id "ongoing-recording"}]
    (with-redefs-fn
      {#'studio/session (atom capture)
       #'studio/armed (atom nil)
       #'studio/project (atom {:revision 12})
       #'studio/render! (fn [f] (f))
       #'studio/set-monitor-level! #(swap! levels conj %)}
      (fn []
        (doseq [percent [0 15 50]]
          (let [command (#'studio/validate-command!
                          {:op :routing/monitor-level :args {:percent percent}})]
            (is (:accepted? (#'studio/execute-command! command)))))
        (is (= [0 15 50] @levels))
        (is (= capture @studio/session))
        (is (= 12 (:revision @studio/project)))))))

(deftest output-stop-reports-recovery-without-changing-takes
  (doseq [mask [0 1 2 3]]
    (let [events (atom [])
          warnings (atom [])]
      (with-redefs-fn
        {#'studio/render! (constantly mask)
         #'studio/emit-event! #(swap! events conj %)
         #'studio/warning! #(swap! warnings conj %)
         #'studio/change-takes! (fn [& _] (throw (AssertionError. "Output loss must not edit takes")))}
        (fn []
          (#'studio/check-playback-devices!)
          (is (= (if (pos? mask) [{:type :audio/output-stopped :mask mask}] []) @events))
          (is (= (if (pos? mask) 1 0) (count @warnings)))
          (when (pos? mask)
            (is (re-find #"Position retained.*Play retries" (first @warnings)))))))))

(deftest failed-audition-retry-does-not-reload-the-take
  ;; Test both the focused take control and the general transport. A failed
  ;; reopen is not a request to start the file again from zero.
  (doseq [[play results] [[#'studio/audition-transport! [false true false]]
                         [#'studio/play-transport! [true false]]]]
    (let [remaining (atom results)]
      (with-redefs-fn
        {#'studio/render! (fn [_]
                           (let [result (first @remaining)]
                             (swap! remaining rest)
                             result))
         #'studio/preview! #(throw (AssertionError. "Keep the decoded take and cursor"))}
        (fn []
          (is (= :playback-device
                 (try (play) nil
                      (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))
          (is (empty? @remaining)))))))

(deftest stopped-preflight-and-monitor-do-not-stop-recording
  (doseq [mask [8 4]]
    (let [state (atom mask)
          calls (atom [])
          session (atom {:id "ongoing-recording"})]
      (with-redefs-fn
        {#'studio/session session
         #'studio/input-check (atom {:source 0})
         #'recorder/stopped-device-mask #(deref state)
         #'recorder/stop-input-check! #(do (swap! calls conj :check-stopped) (reset! state 0))
         #'recorder/stop-monitor! #(do (swap! calls conj :monitor-stopped) (reset! state 0))
         #'recorder/stop! #(throw (AssertionError. "Unrelated recording must continue"))
         #'studio/render! (fn [_] nil)
         #'studio/warning! #(swap! calls conj [:warning %])
         #'studio/emit-event! #(swap! calls conj [:event %])}
        (fn []
          (#'studio/check-audio-devices!)
          (is (= {:id "ongoing-recording"} @session))
          (is (some #{(if (= mask 8) :check-stopped :monitor-stopped)} @calls))
          (is (= 1 (count (filter #(and (vector? %) (= :warning (first %))) @calls))))
          (let [count-before (count @calls)]
            (#'studio/check-audio-devices!)
            (is (= count-before (count @calls)) "A stopped device warns only once")))))))

(deftest interrupted-save-retains-each-available-stream
  (doseq [[wet dry expected] [[0 480 [:dry]] [480 0 [:wet]] [0 0 []] [480 960 [:wet :dry]]]]
    (let [session (atom {:id "voice-test" :path "wet.wav" :dry-path "dry.wav" :processed? true})
          takes (atom {})
          writes (atom [])
          events (atom [])]
      (with-redefs-fn
        {#'studio/session session
         #'studio/takes takes
         #'studio/checkpoint! (fn [force?] (is force?))
         #'recorder/available-frames #(if % wet dry)
         #'recorder/write-take! (fn [path processed?]
                                 (swap! writes conj (if processed? :wet :dry))
                                 true)
         #'studio/remember! (fn [id kind path]
                              (swap! takes update-in [id :history] (fnil conj []) {:path path :kind kind}))
         #'studio/change-takes! (fn [_ change] (swap! takes change))
         #'studio/render! (fn [_] nil)
         #'studio/emit-event! #(swap! events conj %)}
        (fn []
          (is (string? (#'studio/save-interrupted-take!)))
          (is (= expected @writes) "Do not discard dry PCM merely because the FX return is empty")
          (is (nil? @session))
          (is (every? :interrupted? (get-in @takes ["voice-test" :history])))
          (is (= expected (mapv :kind (:saved (last @events))))))))))

(deftest recovered-journals-retain-capture-provenance
  ;; Exercise real journal/WAV/project I/O in a disposable directory. Only the
  ;; recovery discovery root and UI message are substituted, never the codec or
  ;; remember!/project-commit path. Do not run API tests in a live Studio JVM.
  (doseq [hash [(studio/dialogue-fingerprint 86 "Words at capture time") nil]
          failure-stage [nil :project :finalization]]
    (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                              "studio-recovery-provenance-"
                              (make-array java.nio.file.attribute.FileAttribute 0)))
          manifest (io/file directory "session.edn")
          project-file (io/file directory "project.edn")
          buffer (doto (java.nio.ByteBuffer/allocate 32)
                   (.order java.nio.ByteOrder/LITTLE_ENDIAN))
          _ (dotimes [_ 4]
              (.putFloat buffer 0.25)
              (.putFloat buffer -0.25))
          pcm (.array buffer)
          project (atom (files/new-project {}))
          takes (atom {})
          messages (atom [])
          failure-pending (atom failure-stage)
          write-state! files/atomic-edn!]
      (doseq [name ["dry.pcm" "wet.pcm"]]
        (with-open [out (io/output-stream (io/file directory name))]
          (.write out pcm)))
      (files/atomic-edn! manifest
        (cond-> {:id "voice-recovery" :completed? false
                 :streams {:dry {:file "dry.pcm" :frames 3}
                           :wet {:file "wet.pcm" :frames 2}}}
          hash (assoc :dialogue-hash hash)))
      (with-redefs-fn
        {#'studio/session (atom nil)
         #'studio/armed (atom nil)
         #'studio/project project
         #'studio/project-file project-file
         #'studio/takes takes
         #'studio/message! #(swap! messages conj %)
         #'files/atomic-edn!
         (fn [path state]
           (if (or (and (= :project @failure-pending) (= project-file path))
                   (and (= :finalization @failure-pending)
                        (= manifest path) (:completed? state)))
             (do
               (reset! failure-pending nil)
               (throw (ex-info "Simulated recovery write failure" {})))
             (write-state! path state)))
         #'clojure.core/file-seq
         (fn [root]
           (is (= "build/recording/recovery" (str root)))
           [manifest])}
        (fn []
          (when failure-stage
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"recovery write failure"
                  (studio/recover!)))
            (is (false? (:completed? (files/read-state manifest nil))))
            (is (= (if (= :project failure-stage) 0 2)
                   (count (get-in @takes ["voice-recovery" :history]))))
            ;; Model reopening the project after a crash, not just retrying
            ;; against the same in-memory index.
            (reset! project (files/read-state project-file @project))
            (reset! takes (:takes @project)))
          (is (= (if (= :finalization failure-stage) 0 2) (studio/recover!))
              "Retry must finalize already-committed recovery, not import it again")
          (let [history (get-in @takes ["voice-recovery" :history])
                saved (files/read-state project-file nil)]
            (is (= 2 (count history)))
            (is (= 1 (:revision saved)) "Both streams recover as one project edit")
            (is (= ["Recover takes"] (mapv :label (:undo saved))))
            (is (= 2 (count (filter #(.endsWith (.getName %) ".wav")
                                   (.listFiles directory))))
                "Failed project/manifest writes must not multiply WAV files")
            (is (= #{:dry :wet} (set (map :kind history))))
            (is (= @takes (:takes saved)))
            (doseq [{:keys [kind path dialogue-hash interrupted?] :as entry} history]
              (is (= {:manifest (.getCanonicalPath manifest)
                      :kind kind :frames (if (= :dry kind) 3 2)}
                     (:recovery-source entry)))
              (is interrupted?)
              (is (= hash dialogue-hash))
              (is (= :interrupted
                     (studio/recording-freshness
                       (studio/dialogue-fingerprint 86 "Edited after capture") entry)))
              (is (= (vec (take (* 8 (if (= :dry kind) 3 2)) pcm))
                     (vec (files/pcm path)))
                  "Recover only the durable prefix, not uncommitted PCM"))
            (doseq [name ["dry.pcm" "wet.pcm"]]
              (is (= (vec pcm)
                     (vec (java.nio.file.Files/readAllBytes
                            (.toPath (io/file directory name)))))))
            (is (:completed? (files/read-state manifest nil)))
            (is (:recovered? (files/read-state manifest nil)))
            (is (= 0 (studio/recover!)))
            (is (= history (get-in @takes ["voice-recovery" :history])))
            (is (= saved (files/read-state project-file nil)))
            (is (= 2 (count @messages)))))))))

(deftest interrupted-save-failure-keeps-session-for-stop-retry
  (let [initial {:id "voice-test" :path "wet.wav" :processed? true :device-interruption 1}
        session (atom initial)]
    (with-redefs-fn
      {#'studio/session session
       #'studio/checkpoint! (fn [_] nil)
       #'recorder/available-frames (constantly 480)
       #'recorder/write-take! (constantly false)}
      (fn []
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"PCM retained"
              (#'studio/save-interrupted-take!)))
        (is (= initial @session))))))

(deftest input-check-blocks-routing-without-changing-project
  (with-redefs [studio/input-check (atom {:deadline-ns Long/MAX_VALUE})
                studio/session (atom nil)
                studio/armed (atom nil)]
    (doseq [op [:record/dry :record/fx :routing/connect :routing/next :routing/monitor]]
      (is (= :input-check-busy
             (try (#'studio/execute-command! {:op op :args {}})
                  (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))))))

(deftest grouped-state-assignment-expansion
  (let [expand #(apply #'studio/set-state! [nil nil %])]
    (is (= '(do (set! selected next-selection)
                (set! record-track selected)
                (set! clicked false))
           (expand '[selected next-selection
                     record-track selected
                     clicked false]))
        "Expressions occur once, in order; later assignments see earlier changes")
    (is (= '(do (set! scene/vertices output)
                (set! scene/vertex-count 0))
           (expand '[scene/vertices output
                     scene/vertex-count 0])))
    (doseq [invalid [nil [] '(selected 1) '[selected]
                     '[:selected 1] '[(field state selected) 1]]]
      (is (thrown? IllegalArgumentException (expand invalid))))))

(deftest grouped-jvm-native-writes-preserve-evaluation-order
  (let [expand #(apply #'studio/set-native-state! [nil nil %])
        fields (atom {:first 0 :second 0})
        writes (atom [])
        form (expand '[first-field (inc (aguafria.zig/value first-field))
                       second-field (inc (aguafria.zig/value first-field))])]
    (is (= '(do
              (aguafria.zig/set-value! first-field (inc (aguafria.zig/value first-field)))
              (aguafria.zig/set-value! second-field (inc (aguafria.zig/value first-field))))
           form))
    (with-redefs [az/value #(get @fields %)
                  az/set-value! (fn [field value]
                                  (swap! writes conj [field value])
                                  (swap! fields assoc field value)
                                  value)]
      (is (= 2 (eval (list 'let '[first-field :first second-field :second] form)))))
    (is (= [[:first 1] [:second 2]] @writes))
    (is (= {:first 1 :second 2} @fields))
    (doseq [invalid [nil [] '(selected 1) '[selected]
                     '[:selected 1] '[(field state selected) 1]]]
      (is (thrown? IllegalArgumentException (expand invalid))))))

;; These command fixtures are intentionally hardware-free and must run in an
;; isolated JVM. Every touched native Var is explicit: unexpected access fails
;; instead of silently falling through to the live audio or window engine.
(deftest saved-waveform-is-reloaded-after-live-capture
  (let [cache (atom ["voice-a" "saved.wav"])
        uploaded (atom {})
        decoded (atom [])
        names (atom [])
        writes (atom [])]
    (with-redefs-fn
      {#'studio/display-cache cache
       #'studio/session (atom {:id "voice-a" :processed? false})
       #'studio/upload-selected-waveform! (fn [_ upload!] (upload!) true)
       #'studio/selected-waveform? (constantly true)
       #'studio/render! (fn [f] (f))
       #'studio/name-focused? (constantly false)
       #'az/set-value! (fn [field value] (swap! writes conj [field value]))
       #'studio/name! (fn [_])
       #'studio/reset-take-name! #(swap! names conj %)
       #'studio/set-wave! (fn [i value] (swap! uploaded assoc i value))
       #'recorder/wave-bin (fn [_ _] 0.8)
       #'files/waveform (fn [path]
                         (swap! decoded conj path)
                         {:frames 96000 :bins (vec (repeat 128 0.2))})}
      (fn []
        (#'studio/refresh-selected-waveform! "voice-a" "saved.wav" {} true)
        (is (nil? @cache) "Live PCM invalidates saved-file display identity")
        (is (= #{0.8} (set (vals @uploaded))))
        (#'studio/refresh-selected-waveform! "voice-a" "saved.wav" {:name "Original take"} false)
        (is (= ["saved.wav"] @decoded) "Reload even when selected file did not change")
        (is (= #{0.2} (set (vals @uploaded))))
        (is (= ["voice-a" "saved.wav"] @cache))
        (is (= 2.0 (second (first @writes))))
        (is (= ["Original take"] @names))
        (#'studio/refresh-selected-waveform! "voice-a" "saved.wav" {} false)
        (is (= 1 (count @decoded)) "Idle frames keep immutable WAV caching")
        (is (= ["Original take"] @names) "Idle refresh does not replace a name draft")
        (reset! studio/session {:id "voice-b" :processed? false})
        (#'studio/refresh-selected-waveform! "voice-b" nil {} true)
        (#'studio/refresh-selected-waveform! "voice-b" nil {} false)
        (is (= #{0.0} (set (vals @uploaded))))
        (is (= ["voice-b" nil] @cache))
        (is (= ["Original take" ""] @names)
            "Live then empty target clears the previous take's name")))))

(deftest obsolete-waveform-upload-does-not-change-the-current-passage
  (let [selected (atom "new")
        uploads (atom [])
        owners (atom [])]
    (with-redefs-fn
      {#'studio/render! (fn [f] (f))
       #'studio/waveform-passage-id #(deref selected)
       #'studio/native-string identity
       #'studio/waveform-owner! #(swap! owners conj %)}
      (fn []
        (is (nil? (#'studio/upload-selected-waveform! "old" #(swap! uploads conj :old))))
        (is (empty? @uploads))
        (is (empty? @owners))
        (is (true? (#'studio/upload-selected-waveform! "new" #(swap! uploads conj :new))))
        (is (= [:new] @uploads))
        (is (= ["new"] @owners))))))

(deftest capture-script-snapshot-is-coherent-and-rejects-obsolete-targets
  (let [passage (atom {:index 1 :id "voice-a" :revision 1 :speaker 86 :text "Original words"})
        uploaded (atom [])]
    (with-redefs-fn
      {#'studio/render! (fn [f] (f))
       #'az/value (fn [field]
                   (assert (identical? field studio/selected))
                   1)
       #'studio/passage-data (fn [_] @passage)
       #'studio/capture-script! (fn [id text]
                                 (swap! uploaded conj [id text])
                                 true)}
      (fn []
        (let [snapshot (#'studio/capture-script-snapshot! "voice-a")]
          (swap! passage assoc :revision 2 :text "Edited while recording")
          (is (= "Original words" (:text snapshot)))
          (is (= (studio/dialogue-fingerprint 86 "Original words") (:dialogue-hash snapshot)))
          (is (= :changed (studio/recording-freshness
                           (studio/dialogue-fingerprint 86 (:text @passage)) snapshot)))
          (is (= [["voice-a" "Original words"]] @uploaded))
          (is (= :stale-dialogue
                 (try (#'studio/capture-script-snapshot! "voice-removed")
                      (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))
          (is (= 1 (count @uploaded)) "Reject before replacing the frozen script"))))))

(deftest live-waveform-never-belongs-to-another-passage
  (let [cache (atom nil)
        writes (atom [])]
    (with-redefs-fn
      {#'studio/session (atom {:id "recording-a" :processed? false})
       #'studio/display-cache cache
       #'studio/upload-selected-waveform! (fn [_ upload!] (upload!) true)
       #'az/set-value! (fn [field value]
                         (assert (identical? field studio/take-seconds))
                         (is (zero? value)))
       #'studio/set-wave! (fn [_ value] (swap! writes conj value))
       #'studio/reset-take-name! (fn [name] (is (= "" name)))
       #'recorder/wave-bin (fn [& _] (throw (AssertionError. "Wrong passage must not read live PCM")))}
      (fn []
        (#'studio/refresh-selected-waveform! "browsing-b" nil nil true)
        (is (= ["browsing-b" nil] @cache))
        (is (= 128 (count @writes)))
        (is (every? zero? @writes))))))

(defn- with-control-state [initial run-test]
  (let [state (atom initial)
        events (atom [])
        fields {:selected studio/selected
                :focus-scroll studio/focus-scroll
                :record-track studio/record-track
                :record-mode studio/record-mode
                :record-enabled studio/record-enabled
                :workspace-mode studio/workspace-mode
                :track-offset studio/track-offset
                :record-scroll studio/record-scroll
                :countdown-seconds studio/countdown-seconds
                :busy studio/busy
                :capture-phase studio/capture-phase
                :passage-count scene/passage-entity-count
                :input-count recorder/capture-count
                :output-count recorder/playback-count
                :microphone studio/microphone
                :effects-output studio/effects-output
                :return-input studio/return-input
                :headphones studio/headphones
                :preview-paused studio/preview-paused
                :preview-node studio/preview-node
                :mix-mode studio/mix-mode
                :seek-seconds studio/seek-seconds
                :trim-in studio/trim-in
                :trim-out studio/trim-out
                :loop-from studio/loop-from
                :loop-to studio/loop-to
                :mix-duration mixer/duration
                :mix-opened mixer/opened}
        key-for (fn [field]
                  (or (some (fn [[key candidate]]
                              (when (identical? candidate field) key))
                            fields)
                      (throw (AssertionError. "Unexpected native field in control test"))))]
    (with-redefs-fn
      {#'studio/session (atom nil)
       #'studio/armed (atom nil)
       #'studio/project (atom {:revision 12})
       #'studio/render! (fn [f]
                          (swap! events conj [:render])
                          (f))
       #'az/value (fn [field]
                    (let [key (key-for field)]
                      (when-not (contains? @state key)
                        (throw (AssertionError. (str "Uninitialized test field: " key))))
                      (get @state key)))
       #'az/set-value! (fn [field value]
                         (let [key (key-for field)]
                           (swap! events conj [:set key value])
                           (swap! state assoc key value)
                           value))
       #'studio/node-id #(nth ["voice-one" "voice-two"] %)
       #'studio/capture-script-snapshot! (fn [id] {:id id :dialogue-hash "test-script"})
       #'studio/record-row-count (constantly 8)
       #'studio/visible-row-count (constantly 6)
       #'studio/native-string identity
       #'studio/close-playback! #(swap! events conj [:close-playback])
       #'mixer/close! #(swap! events conj [:close-mix])}
      #(run-test state events))))

(deftest passage-selection-and-arming-command-order
  (with-control-state
    {:passage-count 2 :selected 1 :focus-scroll 28.0 :record-track 1
     :workspace-mode 1 :track-offset 1 :record-scroll 42.0}
    (fn [state events]
      (is (:accepted? (#'studio/execute-command!
                       {:op :selection/passage :args {:id "voice-one"}})))
      (is (= [[:render] [:set :selected 0] [:set :track-offset 0]
              [:set :record-scroll 0.0] [:set :focus-scroll 0.0]] @events))
      (reset! events [])
      (#'studio/execute-command! {:op :record/arm :args {:id "voice-one" :enabled false}})
      (is (= 1 (:record-track @state)) "Disarming another row leaves the armed row alone")
      (is (= [[:render]] @events))
      (#'studio/execute-command! {:op :record/arm :args {:id "voice-one" :enabled true}})
      (is (= 0 (:record-track @state)) "Row zero is a valid recording target")
      (#'studio/execute-command! {:op :record/arm :args {:id "voice-one" :enabled false}})
      (is (= 4294967295 (:record-track @state)))
      (let [before @state]
        (is (= :not-found
               (try
                 (#'studio/execute-command! {:op :selection/passage :args {:id "missing"}})
                 (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))
        (is (= before @state))))))

(deftest routing-validation-precedes-engine-and-state-changes
  (let [initial {:input-count 2 :output-count 3
                 :microphone 0 :effects-output 0 :return-input 0
                 :headphones 0 :preview-paused true}
        route {:source 1 :send 0 :return 1 :headphones 2}]
    (with-control-state
      initial
      (fn [state events]
        (doseq [missing [:source :return :send :headphones]]
          (is (= :not-found
                 (try
                   (#'studio/execute-command!
                    {:op :routing/select :args (assoc route missing 5)})
                   (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))))
        (is (= initial @state))
        (is (= (repeat 4 [:render]) @events) "Invalid routes cannot close or mutate anything")
        (reset! events [])
        (is (= route (:result (#'studio/execute-command! {:op :routing/select :args route}))))
        (is (= [[:render] [:close-playback] [:close-mix] [:set :preview-paused false]
                [:set :microphone 1] [:set :effects-output 0]
                [:set :return-input 1] [:set :headphones 2]]
               @events))
        (reset! events [])
        (#'studio/execute-command! {:op :routing/select :args route})
        (is (not-any? #(#{:close-playback :close-mix} (first %)) @events)
            "Reapplying the same output does not close either engine")))))

(deftest mix-loop-command-keeps-frame-and-ui-coordinates-consistent
  (with-control-state
    {:mix-duration 480000 :loop-from 0.0 :loop-to 0.0}
    (fn [state events]
      (let [sources (atom [])
            loop-calls (atom [])
            command {:op :mix/loop :args {:from 0.25 :to 1.5 :enabled true}}]
        (with-redefs-fn
          {#'studio/mix-sources sources
           #'mixer/set-loop! (fn [& args]
                              (swap! loop-calls conj (vec args))
                              true)}
          (fn []
            (is (= :mix-unprepared
                   (try (#'studio/execute-command! command)
                        (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))
            (is (empty? @loop-calls))
            (is (empty? @events))
            (reset! sources [{:id "clip"}])
            (is (= {:from-frame 12000 :to-frame 72000 :enabled true}
                   (:result (#'studio/execute-command! command))))
            (is (= [[12000 72000 true]] @loop-calls))
            (is (= {:loop-from 0.25 :loop-to 1.5}
                   (select-keys @state [:loop-from :loop-to])))
            (is (= :invalid-loop
                   (try
                     (#'studio/execute-command! (assoc-in command [:args :to] 11))
                     (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))
            (is (= 1 (count @loop-calls)) "Reject out-of-bounds loop before the native setter")))))))

(deftest take-edit-commands-retain-operation-order
  (with-control-state
    {:trim-in 0 :trim-out 100}
    (fn [state events]
      (with-redefs-fn
        {#'studio/stop-mix! #(swap! events conj [:stop-mix])
         #'studio/refresh-display! #(swap! events conj [:refresh-display])
         #'studio/name! #(swap! events conj [:name %])
         #'studio/edit-take! #(swap! events conj [:edit %])}
        (fn []
          (#'studio/execute-command! {:op :take/name :args {:name "entrée"}})
          (is (= [[:stop-mix] [:render] [:name "entrée"] [:edit :name]] @events))
          (reset! events [])
          (is (= :invalid-argument
                 (try
                   (#'studio/execute-command!
                    {:op :take/name :args {:name (apply str (repeat 61 "é"))}})
                   (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))
          (is (= [[:stop-mix]] @events) "UTF-8 byte bounds reject before writing the name")
          (reset! events [])
          (#'studio/execute-command! {:op :take/trim :args {:from 12 :to 90}})
          (is (= [[:stop-mix] [:refresh-display] [:render]
                  [:set :trim-in 12] [:set :trim-out 90] [:edit :trim]]
                 @events))
          (is (= {:trim-in 12 :trim-out 90} @state)))))))

(deftest history-commands-stop-audio-before-restoring-take-state
  (doseq [direction [:undo :redo]]
    (with-control-state
      {:preview-node 1 :seek-seconds 2.5 :preview-paused true}
      (fn [state events]
        (let [restored {"voice-one" {:selected "retained.wav"
                                     :history [{:path "retained.wav" :name "Original"}]}}]
          (with-redefs-fn
            {#'studio/takes (atom {})
             #'studio/display-cache (atom :old)
             #'studio/comparison (atom :old)
             #'studio/stop-mix! #(swap! events conj [:stop-mix])
             #'studio/stop-voice! #(swap! events conj [:stop-voice])
             #'studio/focused-id (constantly "voice-one")
             #'studio/name! #(swap! events conj [:name %])
             #'studio/message! #(swap! events conj [:message %])
             #'files/history-project!
             (fn [project _ actual-direction]
               (swap! events conj [:history actual-direction])
               (swap! project assoc :revision 13 :takes restored))}
            (fn []
              (let [response (#'studio/execute-command!
                              {:op (keyword "project" (name direction)) :args {}})]
                (is (= 13 (:project-revision response)))
                (is (= {:revision 13} (:result response))))
              (is (= [[:stop-mix] [:render] [:stop-voice]
                      [:set :preview-node 4294967295] [:set :seek-seconds 0.0]
                      [:set :preview-paused false] [:history direction]
                      [:render] [:name "Original"]
                      [:message (if (= :undo direction)
                                  "Edit undone. Audio retained."
                                  "Edit redone.")]]
                     @events))
              (is (= {:preview-node 4294967295 :seek-seconds 0.0 :preview-paused false} @state))
              (is (= restored @studio/takes))
              (is (nil? @studio/display-cache))
              (is (nil? @studio/comparison)))))))))

(deftest window-bounds-fit-available-displays
  (let [main {:x 0 :y 25 :width 1728 :height 1067}
        left {:x -1920 :y 0 :width 1920 :height 1080}
        frame {:left 0 :top 28 :right 0 :bottom 0}
        saved {:x 343 :y 168 :width 1100 :height 760}]
    (is (= saved (studio/fit-window-bounds saved [main] frame)))
    (is (= {:x 0 :y 53 :width 1728 :height 1039}
           (studio/fit-window-bounds {:x -5000 :y -2000 :width 9000 :height 7000} [main] frame)))
    (is (= {:x 628 :y 332 :width 1100 :height 760}
           (studio/fit-window-bounds (assoc saved :x 4000 :y 2000) [main] frame)))
    (is (= {:x -1800 :y 100 :width 1100 :height 760}
           (studio/fit-window-bounds (assoc saved :x -1800 :y 100) [main left] frame)))
    (is (= {:x 0 :y 100 :width 1100 :height 760}
           (studio/fit-window-bounds (assoc saved :x -1800 :y 100) [main] frame)))
    (is (= 1100 (:width (studio/fit-window-bounds (assoc saved :width 10) [main] frame))))
    (doseq [bad [(assoc saved :width -1) (assoc saved :x Double/NaN) (assoc saved :y "200") nil]]
      (is (thrown? clojure.lang.ExceptionInfo (studio/fit-window-bounds bad [main] frame))))
    (is (= :display-too-small
           (try (studio/fit-window-bounds saved [{:x 0 :y 0 :width 800 :height 600}] frame)
                (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))))

(deftest window-preferences-debounce-and-normal-bounds
  (let [path (java.nio.file.Files/createTempFile "studio-window-qa-" ".edn"
               (make-array java.nio.file.attribute.FileAttribute 0))
        file (.toFile path) state (atom nil)
        normal {:x 10 :y 50 :width 1100 :height 760 :normal 1}
        read! #(edn/read-string (slurp file))]
    (try
      (studio/save-window-preferences! normal file state 0 false)
      (is (zero? (.length file)) "Don't write on every motion event")
      (studio/save-window-preferences! normal file state 499999999 false)
      (is (zero? (.length file)))
      (studio/save-window-preferences! normal file state 500000000 false)
      (is (= {:version 1 :bounds (dissoc normal :normal)} (read!)))
      (let [saved @state]
        (studio/save-window-preferences! (assoc normal :normal 0 :width 1728) file state 1000000000 true)
        (is (= saved @state) "Maximized/minimized geometry is ignored even at close"))
      (studio/save-window-preferences! (assoc normal :width 1400) file state 1000000000 false)
      (is (= 1100 (get-in (read!) [:bounds :width])))
      (studio/save-window-preferences! (assoc normal :width 1400) file state 1000000001 true)
      (is (= 1400 (get-in (read!) [:bounds :width])) "Close flushes the stable normal bounds")
      (is (:saved? @state))
      (studio/save-window-preferences! (assoc normal :width 1300) file state 2000000000 false)
      (studio/save-window-preferences! (assoc normal :normal 0 :width 1728) file state 2000000001 true)
      (is (= 1300 (get-in (read!) [:bounds :width])) "Closing maximized flushes the prior normal bounds")
      (studio/save-window-preferences! (assoc normal :routing-visible? false) file state 3000000000 true)
      (is (= {:routing-visible? false} (:panels (read!))))
      (studio/save-window-preferences! (assoc normal :routing-visible? true) file state 4000000000 false)
      (is (false? (get-in (read!) [:panels :routing-visible?])) "Panel-only edits use the same debounce")
      (studio/save-window-preferences! (assoc normal :routing-visible? true) file state 4500000000 false)
      (is (true? (get-in (read!) [:panels :routing-visible?])))
      (studio/save-window-preferences! (assoc normal :routing-visible? true :editor-top 380.0) file state 5000000000 true)
      (is (= {:routing-visible? true :editor-top 380.0} (:panels (read!))))
      (let [failed (atom nil) impossible (java.io.File. file "child.edn")]
        (is (thrown? Exception (studio/save-window-preferences! normal impossible failed 0 true)))
        (is (string? (:error @failed)))
        (is (nil? (studio/save-window-preferences! normal impossible failed 1000000000 false))
            "Don't retry a failed disk write on every display refresh")
        (is (thrown? Exception (studio/save-window-preferences! normal impossible failed 1000000001 true))
            "Explicit flush may retry"))
      (finally (java.nio.file.Files/deleteIfExists path)))))

(deftest routing-visibility-api-schema
  ;; Validate only: don't replace the live worker or enqueue audio commands.
  (doseq [op [:view/routing :view/routing-tools]
          visible [true false]]
    (let [command {:op op :args {:visible visible}}]
      (is (= command (#'studio/validate-command! command)))))
  (doseq [op [:view/routing :view/routing-tools]
          args [{} {:visible 1} {:visible "true"} {:visible nil} {:visible false :mute true}]]
    (is (thrown? clojure.lang.ExceptionInfo
          (#'studio/validate-command! {:op op :args args})))))

(deftest workspace-mode-api-schema
  (is (= #{:edit :record :takes} (set (keys (:workspace-modes (studio/capabilities))))))
  (is (= {:mode :workspace-mode} (get-in (studio/capabilities) [:commands :view/mode])))
  (doseq [mode [:edit :record :takes]]
    (let [command {:op :view/mode :args {:mode mode}}]
      (is (= command (#'studio/validate-command! command)))))
  (doseq [args [{} {:mode nil} {:mode :take-grid} {:mode "record"}
                {:mode :record :record true}]]
    (is (thrown? clojure.lang.ExceptionInfo
          (#'studio/validate-command! {:op :view/mode :args args})))))

(deftest take-grid-api-and-snapshot-contract
  (let [take-state {"voice-a" {:dry "/qa/dry.wav"
                               :wet "/qa/fx.wav"
                               :preferred "/qa/fx.wav"
                               :selected "/qa/dry.wav"
                               :history [{:path "/qa/dry.wav" :kind :dry :name "Clean"}
                                         {:path "/qa/fx.wav" :kind :wet}]}}
        specs (#'studio/take-grid-specs ["voice-a" ""] take-state)
        snapshot {:revision 4 :cards [(assoc (first specs) :available? true)]}]
    (is (= 6 (count specs)))
    (is (= [:dry :wet :preferred :dry :wet :preferred] (mapv :kind specs)))
    (is (= [true false false false false false] (mapv :chosen? specs)))
    (is (= ["Clean" "Take 2" "Take 2" nil nil nil] (mapv :name specs)))
    (is (= {:op :take/select :args {:id "voice-a" :path "/qa/dry.wav"}}
           (#'studio/take-card-command snapshot 0 4 false)))
    (is (= :take/audition (:op (#'studio/take-card-command snapshot 0 4 true))))
    (doseq [[slot revision code] [[0 3 :stale-view] [1 4 :not-found]]]
      (is (= code (try (#'studio/take-card-command snapshot slot revision false)
                       (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))))
    (is (= "Clean" (:name (#'studio/known-take take-state "voice-a" "/qa/dry.wav"))))
    (is (thrown? clojure.lang.ExceptionInfo
          (#'studio/known-take take-state "other" "/qa/dry.wav")))
    (is (nil? (:path (first (#'studio/take-grid-specs ["voice-a"]
                               (-> take-state
                                   (assoc-in ["voice-a" :selected] "/not/in/history.wav")
                                   (assoc-in ["voice-a" :dry] "/not/in/history.wav")))))))
    (let [older (-> take-state
                   (assoc-in ["voice-a" :selected] "/qa/older-fx.wav")
                   (update-in ["voice-a" :history] conj {:path "/qa/older-fx.wav" :kind :wet}))
          cards (#'studio/take-grid-specs ["voice-a"] older)]
      (is (= "/qa/older-fx.wav" (:path (second cards))))
      (is (true? (:chosen? (second cards))))
      (is (= "/qa/fx.wav" (:path (nth cards 2))) "Favorite retains its explicit reference"))
    (let [missing (#'studio/take-card-waveform (first specs))]
      (is (true? (:missing? missing)))
      (is (false? (:available? missing))))
    (doseq [op [:take/select :take/audition]]
      (let [command {:op op :args {:id "voice-a" :path (str "/qa/" (apply str (repeat 200 "x")) ".wav")}}]
        (is (= command (#'studio/validate-command! command)))))
    (doseq [args [{:slot -1 :revision 1 :audition true}
                 {:slot 48 :revision 1 :audition true}
                 {:slot 0 :revision -1 :audition true}
                 {:slot 0 :revision 1 :audition 1}]]
      (is (thrown? clojure.lang.ExceptionInfo
            (#'studio/validate-command! {:op :take/card :args args}))))))

(deftest editor-divider-api-schema
  (let [command {:op :view/editor :args {:top 520.0}}]
    (is (= command (#'studio/validate-command! command))))
  (doseq [top [-1 Double/NaN Double/POSITIVE_INFINITY "520" nil]]
    (is (thrown? clojure.lang.ExceptionInfo
          (#'studio/validate-command! {:op :view/editor :args {:top top}})))))

(deftest focus-marshals-to-render-thread
  (let [queued (promise) completed (promise) calls (atom [])]
    (with-redefs [core/on-render! (fn [f] (deliver queued f) completed)
                  studio/focus-window-native! #(swap! calls conj (Thread/currentThread))]
      (let [request (future (studio/focus-window!))
            callback (deref queued 2000 nil)]
        (try
          (is (fn? callback))
          (is (empty? @calls) "Enqueuing must not touch native window state")
          (when callback (callback))
          (is (= [(Thread/currentThread)] @calls))
          (finally (deliver completed {:value :focused})))
        (is (= :focused (deref request 2000 :timeout)))))))

(deftest focused-recording-target-is-explicit
  (with-control-state
    {:passage-count 2 :selected 1 :record-track 0 :record-mode 8
     :record-enabled 0 :workspace-mode 1 :countdown-seconds 30
     :preview-node 1 :preview-paused true :seek-seconds 2.0
     :busy 0 :capture-phase 0}
    (fn [state events]
      (with-redefs-fn
        {#'recorder/initialize! #(do (swap! events conj [:initialize]) true)
         #'studio/stop-mix! #(swap! events conj [:stop-mix])
         #'studio/stop-voice! #(swap! events conj [:stop-voice])
         #'studio/alert! (fn [_])
         #'studio/message! (fn [_])
         #'studio/begin-countdown-clock! #(swap! events conj [:countdown])}
        (fn []
          (let [project-before @studio/project]
            (is (:accepted? (#'studio/execute-command!
                             {:op :record/start :args {:id "voice-two"}})))
            (is (= 1 (:selected @state)))
            (is (= 1 (:record-track @state)) "The previous Edit arm cannot redirect Record")
            (is (= {:id "voice-two" :action 8}
                   (select-keys @studio/armed [:id :action])))
            (is (zero? (:record-enabled @state)) "Direct Record does not depend on global REC")
            (is (= project-before @studio/project) "Count-in creates no take or project edit")
            (let [scheduled @studio/armed]
              (is (= :recording-busy
                     (try (#'studio/execute-command!
                            {:op :record/start :args {:id "voice-one"}})
                          (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))
              (is (= scheduled @studio/armed)))
            (#'studio/execute-command! {:op :transport/stop :args {}})
            (is (nil? @studio/armed))
            (is (nil? @studio/session))
            ;; Next press schedules a new passage, not a resumed/old take.
            (#'studio/execute-command! {:op :record/start :args {:id "voice-one"}})
            (is (= 0 (:selected @state)))
            (is (= "voice-one" (:id @studio/armed)))
            (#'studio/execute-command! {:op :transport/stop :args {}})
            (doseq [[id code] [["" :not-recordable] ["missing" :not-found]]]
              (let [before @state]
                (reset! events [])
                (is (= code
                       (try (#'studio/schedule-passage! 1 id)
                            (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))
                (is (= before @state))
                (is (= [[:render]] @events) "Reject before device or playback side effects")))))))))

(deftest focused-play-is-audition-even-with-global-rec-enabled
  (doseq [mode [1 2]]
    (with-control-state
      {:workspace-mode mode :record-enabled 1 :mix-mode false}
      (fn [_ events]
        (with-redefs-fn
          {#'studio/playing-preview? (constantly false)
           #'studio/paused-preview? (constantly false)
           #'studio/selected-preview? (constantly false)
           #'studio/resume-preview! (constantly false)
           #'studio/preview! #(swap! events conj [:audition])
           #'studio/schedule! (fn [_] (throw (AssertionError. "Play must not record outside Edit")))}
          #(#'studio/execute-command! {:op :transport/play :args {}}))
        (is (= [:audition] (last @events)))))))

(deftest global-resume-ignores-new-selection-and-record-toggle
  (doseq [mode [0 1 2]]
    (with-control-state
      {:workspace-mode mode :record-enabled 1 :mix-mode false}
      (fn [_ events]
        (with-redefs-fn
          {#'studio/paused-preview? (constantly true)
           #'studio/selected-preview? (constantly false)
           #'studio/resume-preview! #(do (swap! events conj [:resume]) true)
           #'studio/preview! #(throw (AssertionError. "Do not reload the selected passage"))
           #'studio/schedule! (fn [_] (throw (AssertionError. "Resume must not record")))}
          #(#'studio/execute-command! {:op :transport/play :args {}}))
        (is (= [:resume] (last @events)))))))

(deftest audition-is-explicit-and-recording-guarded
  (is (= {} (get-in (studio/capabilities) [:commands :transport/audition])))
  (is (= {:op :transport/audition :args {}}
         (#'studio/validate-command! {:op :transport/audition :args {}})))
  (is (= :transport/audition (:op (#'studio/ui-command 35))))
  (is (thrown? clojure.lang.ExceptionInfo
        (#'studio/validate-command! {:op :transport/audition :args {:record true}})))
  ;; Reject before touching native state or audio. This fixture must run isolated
  ;; from a live studio worker, just like the API suite's other session fixtures.
  (with-redefs [studio/session (atom {:qa true})]
    (is (= :recording-busy
           (try (#'studio/execute-command! {:op :transport/audition :args {}})
                (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))))

(deftest audition-dispatches-from-native-state-not-the-var-wrapper
  ;; Isolated API process only: do not replace handlers under a live worker.
  (let [calls (atom []) native-value az/value]
    (doseq [[selected? playing? paused? expected]
            [[false false false :play] [true true false :pause]
             [true false true :resume] [true false false :play]]]
      (reset! calls [])
      (with-redefs-fn
        {#'studio/session (atom nil) #'studio/armed (atom nil)
         #'studio/render! (fn [f] (f))
         #'studio/selected-preview? (constantly selected?)
         #'studio/playing-preview? (constantly playing?)
         #'az/value (fn [v] (if (identical? v studio/preview-paused) paused? (native-value v)))
         #'studio/preview! #(swap! calls conj :play)
         #'studio/pause-preview! #(swap! calls conj :pause)
         #'studio/resume-preview! #(swap! calls conj :resume)}
        #(#'studio/execute-command! {:op :transport/audition :args {}}))
      (is (= [expected] @calls)))))

(defn- isolated [f]
  (with-redefs [studio/worker (atom (java.util.concurrent.CompletableFuture.))
                studio/worker-health (atom {:state :running :failures 0})
                studio/command-queue (java.util.concurrent.ArrayBlockingQueue. 64)
                studio/request-ledger (atom {}) studio/completed-requests (atom [])
                studio/event-log (atom {:sequence 0 :events []}) studio/extensions (atom {})]
    (f)))

(deftest failed-native-accessor-does-not-kill-worker
  ;; Isolated JVM only. A failure in the first native accessor must not stop
  ;; native capture, consume a command, or escape the worker's cycle boundary.
  (let [compile-error (ex-info "Invalid edit\nvery large generated source"
                              {:aguafria/phase :zig-compile})
        stops (atom 0)]
    (with-redefs [studio/worker-health (atom {:state :running :failures 0})
                  studio/session (atom {:id "unsaved-pcm"})
                  studio/armed (atom {:action 8 :until Long/MAX_VALUE})
                  studio/render! (fn [f] (f))
                  studio/handle-stopped-outputs! (fn [] (throw compile-error))
                  recorder/stop! #(swap! stops inc)]
      (#'studio/worker-cycle! true)
      (is (= :waiting-for-code (:state @studio/worker-health)))
      (is (= {:phase :zig-compile :message "Invalid edit"} (:error @studio/worker-health)))
      (is (= 1 (:failures @studio/worker-health)))
      (is (zero? @stops))
      (is (= {:id "unsaved-pcm"} @studio/session))
      (is (= 8 (:action @studio/armed)))
      (with-redefs-fn {#'studio/worker-iteration! (fn [_] nil)}
        #(#'studio/worker-cycle! true))
      (is (= :running (:state @studio/worker-health)))
      (is (nil? (:error @studio/worker-health)))
      (is (= "Invalid edit" (get-in @studio/worker-health [:last-error :message]))))))

(deftest worker-error-cleanup-is-guarded-and-interruption-is-preserved
  (with-redefs [studio/worker-health (atom {:state :running :failures 0})
                studio/armed (atom {:action 8 :until 0})
                studio/session (atom {:id "unsaved-pcm"})
                recorder/stop! (fn [] (throw (ex-info "device cleanup failed" {})))]
    (with-redefs-fn {#'studio/worker-iteration! (fn [_] (throw (ex-info "disk full" {})))}
      #(#'studio/worker-cycle! true))
    (is (= :error (:state @studio/worker-health)))
    (is (= "disk full" (get-in @studio/worker-health [:error :message])))
    (is (= "device cleanup failed" (get-in @studio/worker-health [:cleanup-error :message])))
    (is (nil? @studio/armed) "Failed cleanup must not leave a count-in ready to start recording")
    (is (= {:id "unsaved-pcm"} @studio/session))
    (with-redefs-fn {#'studio/worker-iteration! (fn [_] (throw (InterruptedException.)))}
      #(is (thrown? InterruptedException (#'studio/worker-cycle! true))))
    (is (= 1 (:failures @studio/worker-health)))
    (is (= 512 (count (:message (#'studio/worker-error-summary
                                 (Exception. (apply str (repeat 1000 "x"))))))))))

(deftest terminal-worker-rejects-new-commands
  (isolated
    #(do
       (reset! studio/worker (java.util.concurrent.CompletableFuture/completedFuture nil))
       (is (= :stopped (:state (studio/worker-status))))
       (is (false? (:alive? (studio/worker-status))))
       (is (= :worker-stopped
              (try (studio/submit! {:op :transport/play})
                   (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))
       (is (empty? @studio/request-ledger))
       (is (zero? (.size studio/command-queue))))))

(deftest worker-health-never-needs-the-native-renderer
  (isolated
    #(with-redefs-fn {#'studio/render! (fn [_] (throw (AssertionError. "Native publication is unavailable")))}
       (fn []
         (reset! studio/worker-health {:state :waiting-for-code :failures 1})
         (is (= {:state :waiting-for-code :failures 1 :alive? true} (studio/worker-status)))
         (reset! studio/worker nil)
         (is (= :closed (:state (studio/worker-status))))))))

(deftest restarting-a-live-worker-is-rejected-before-native-access
  (isolated
    #(with-redefs-fn {#'studio/render! (fn [_] (throw (AssertionError. "Must not touch the window")))}
       (fn []
         (is (= :worker-running
                (try (studio/restart-worker!)
                     (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))))))

(deftest explicit-recording-control-contract
  (isolated
    #(do
       (doseq [command [{:op :record/arm :args {:id "voice-test" :enabled true}}
                        {:op :record/start :args {:id "voice-test"}}
                        {:op :record/enable :args {:enabled true}}
                        {:op :playback/boost :args {:enabled true}}
                        {:op :view/routing :args {:visible false}}
                        {:op :alert/dismiss}
                        {:op :record/fx} {:op :record/dry} {:op :record/toggle}]]
         (is (= :queued (:status (studio/submit! command)))))
       (doseq [command [{:op :record/enable :args {:enabled 1}}
                        {:op :playback/boost :args {:enabled "yes"}}
                        {:op :view/routing :args {:visible "yes"}}
                        {:op :record/arm :args {:id "voice-test"}}
                        {:op :record/arm :args {:id 12 :enabled true}}
                        {:op :routing/select :args {:source -1 :send 0 :return 0 :headphones 0}}]]
         (is (thrown? clojure.lang.ExceptionInfo (studio/submit! command)))))))

(deftest command-validation-and-capabilities
  (isolated
    #(do
       (is (= true (:multitrack? (studio/capabilities))))
       (is (= false (:arranger? (studio/capabilities))))
       (is (contains? (:commands (studio/capabilities)) :transport/pause))
       (is (true? (get-in (studio/capabilities) [:mix :loop?])))
       (doseq [command [{:op :unknown/command} {:op :transport/seek :args {:seconds Double/NaN}}
                        {:op :transport/seek :args {:seconds -1}} {:op :view/zoom :args {:factor 0}}
                        {:op :take/trim :args {:from 50 :to 10}} {:op :transport/play :args {:typo true}}
                        {:op :mix/loop :args {:from 2 :to 1 :enabled true}}
                        {:op :mix/loop :args {:from 1 :to 1 :enabled true}}
                        {:op :mix/loop :args {:from Double/NaN :to 2 :enabled true}}
                        {:op :mix/loop :args {:from 0 :to 2 :enabled "yes"}}]]
         (is (thrown? clojure.lang.ExceptionInfo (studio/submit! command))))
       (reset! studio/worker nil)
       (is (= :closed (try (studio/submit! {:op :transport/play}) (catch Exception e (:code (ex-data e)))))))))

(deftest optimistic-project-revision
  (isolated
    #(with-redefs [studio/project (atom {:revision 12}) studio/session (atom nil) studio/armed (atom nil)]
       (studio/register-command! :test/revision {:description "Revision fixture" :validate map? :handler identity})
       (is (= :revision-conflict
              (try (#'studio/execute-command! {:op :test/revision :args {} :expected-revision 11})
                   (catch Exception e (:code (ex-data e))))))
       (is (:accepted? (#'studio/execute-command! {:op :test/revision :args {} :expected-revision 12})))
       (is (thrown? Exception (studio/submit! {:op :project/undo :expected-revision -1})))
       (is (thrown? Exception (studio/submit! {:op :project/undo :expected-revision 1.5}))))))

(deftest bounded-idempotent-requests
  (isolated
    #(let [command {:op :transport/play :request-id "same"}]
       (is (= :queued (:status (studio/submit! command))))
       (is (= :known (:status (studio/submit! command))))
       (is (= 1 (.size studio/command-queue)))
       (is (= :pending (:status (studio/result "same"))))
       (is (= :request-conflict (try (studio/submit! (assoc command :op :transport/stop))
                                    (catch Exception e (:code (ex-data e))))))
       (let [ticket (.poll studio/command-queue)]
         (#'studio/complete-request! ticket {:status :done})
         (is (= :done (:status (studio/result "same"))))
         (studio/submit! command)
         (is (zero? (.size studio/command-queue))))
       (dotimes [i 64] (studio/submit! {:op :transport/play :request-id (str i)}))
       (is (= :queue-full (try (studio/submit! {:op :transport/play}) (catch Exception e (:code (ex-data e))))))
       (is (= 65 (count @studio/request-ledger)))
       (is (= :unknown (:status (studio/result "not-found")))))))

(deftest extension-and-event-contract
  (isolated
    #(do
       (studio/register-command! :test/hello {:description "Test extension" :validate map? :handler identity})
       (is (= "Test extension" (get-in (studio/capabilities) [:commands :test/hello :description])))
       (is (nil? (get-in (studio/capabilities) [:commands :test/hello :handler])))
       (is (thrown? clojure.lang.ExceptionInfo
                    (studio/register-command! :transport/play {:description "Override" :validate map? :handler identity})))
       (studio/unregister-command! :test/hello)
       (is (thrown? clojure.lang.ExceptionInfo (studio/submit! {:op :test/hello})))
       (dotimes [i 300] (#'studio/emit-event! {:type :test :value i}))
       (is (= 256 (count (:events (studio/events-since 0)))))
       (is (:resync? (studio/events-since 0)))
       (is (= 300 (:cursor (studio/events-since 299))))
       (is (= [299] (mapv :value (:events (studio/events-since 299)))))
       (is (false? (:resync? (studio/events-since 299))))
       (is (:resync? (studio/events-since 301))))))

(deftest recording-rejection-and-extension-result
  (isolated
    #(do
       (with-redefs [studio/session (atom {:id "retained-take"}) studio/armed (atom nil)]
         (is (= :recording-busy
                (try (#'studio/execute-command! {:op :transport/play :args {}})
                     (catch Exception e (:code (ex-data e))))))
         (is (= {:id "retained-take"} @studio/session)))
       (with-redefs [studio/session (atom nil) studio/armed (atom nil)]
         (studio/register-command! :test/echo {:description "Echo" :validate map? :handler identity})
         (is (= {:ok true} (:result (#'studio/execute-command! {:op :test/echo :args {:ok true}}))))))))

(deftest synchronous-extension-recursion-is-rejected
  (isolated
    #(binding [studio/*command-worker?* true]
       (is (= :reentrant-command
              (try (studio/command! {:op :transport/play})
                   (catch Exception e (:code (ex-data e))))))
       (is (zero? (.size studio/command-queue)))
       ;; Deferred follow-up work does not block the worker and remains supported.
       (is (= :queued (:status (studio/submit! {:op :transport/stop})))))))
