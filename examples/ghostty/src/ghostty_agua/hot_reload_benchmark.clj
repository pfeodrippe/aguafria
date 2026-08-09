(ns ghostty-agua.hot-reload-benchmark
  "Reproducible one-JVM hot-reload measurements for converted Ghostty code."
  (:require [aguafria.zig :as az]
            [aguafria.zig.benchmark :as benchmark]
            [clojure.walk :as walk]
            [ghostty-agua.core :as core]
            [ghostty-agua.live :as live]))

(defn- terminal-address
  []
  (.address ^java.lang.foreign.MemorySegment
            (:terminal (or (core/session) (core/start!)))))

(defn- verified-terminal-value
  [label expected actual address]
  (let [same-terminal? (= address (terminal-address))]
    (when-not (and (= expected actual) same-terminal?)
      (throw (ex-info "Hot-reload behavior did not become observable"
                      {:label label
                       :expected expected
                       :actual actual
                       :same-terminal? same-terminal?})))
    {:value actual
     :same-terminal? same-terminal?}))

(defn- verified-terminal
  [label address]
  (let [same-terminal? (= address (terminal-address))]
    (when-not same-terminal?
      (throw (ex-info "Hot reload replaced the live terminal"
                      {:label label :same-terminal? false})))
    {:same-terminal? true}))

(defn- replace-form
  [descriptor expected replacement]
  (let [replaced? (atom false)
        body (walk/postwalk
              (fn [form]
                (if (and (not @replaced?) (= expected (pr-str form)))
                  (do (reset! replaced? true) (replacement form))
                  form))
              (:body descriptor))]
    (when-not @replaced?
      (throw (ex-info "Benchmark could not find the expected generated form"
                      {:declaration (:name descriptor)
                       :expected expected})))
    (assoc descriptor :body body)))

(defn- fresh-title-version
  [context]
  (+ 1000 (mod (:fresh-value context) 1000000)))

(defn- fresh-focus-character
  [context]
  (char (+ (int \A) (mod (:fresh-value context) 26))))

(defn simple!
  "Measure a compatible hand-written leaf edit with the terminal left open."
  ([] (simple! 2))
  ([version]
   (az/await! 'ghostty-agua.live)
   (let [_ (live/title-version)
         address (terminal-address)]
     (benchmark/measure-edit!
      {:var #'live/title-version
       :project :ghostty
       :complexity :simple
       :label "title leaf"
       :edit #(assoc % :body [version])
       :verify-change
       (fn []
         (verified-terminal-value "title-version change"
                                  version (live/title-version) address))
       :verify-restore
       (fn []
         (verified-terminal-value "title-version restore"
                                  1 (live/title-version) address))}))))

(defn medium!
  "Measure a real converted focus encoder edit through its native bridge."
  []
  (let [encode (requiring-resolve 'ghostty.src.terminal.focus/encode)
        _ (core/focus-final-byte true)
        address (terminal-address)]
    (benchmark/measure-edit!
     {:var encode
      :project :ghostty
      :complexity :medium
      :label "converted focus encoder through bridge"
      :edit #(replace-form %
                           "(string-literal \"\\\"\\\\x1B[I\\\"\")"
                           (fn [_]
                             '(string-literal "\"\\x1B[X\"")))
      :verify-change
      (fn []
        (verified-terminal-value "focus encoder change"
                                 (int \X) (core/focus-final-byte true) address))
      :verify-restore
      (fn []
        (verified-terminal-value "focus encoder restore"
                                 (int \I) (core/focus-final-byte true) address))})))

(defn complex!
  "Measure a method-compatible edit to Ghostty's real generic queue type."
  ([] (complex! 1))
  ([identity-depth]
   (let [blocking-queue
         (requiring-resolve 'ghostty.src.datastruct.blocking-queue/BlockingQueue)
         address (terminal-address)]
     (az/await! 'ghostty.src.datastruct.blocking-queue)
     (benchmark/measure-edit!
      {:var blocking-queue
       :project :ghostty
       :complexity :complex
       :label "real BlockingQueue comptime method body"
       :edit #(replace-form
               %
               "(== (field self len) bounds)"
               (fn [form]
                 (nth (iterate (fn [value] (list 'and value true)) form)
                      identity-depth)))
       :verify-change #(verified-terminal "BlockingQueue change" address)
       :verify-restore #(verified-terminal "BlockingQueue restore" address)}))))

(defn simple-series!
  "Measure fresh hand-written leaf artifacts in one terminal session."
  ([] (simple-series! 5))
  ([samples]
   (az/await! 'ghostty-agua.live)
   (let [address (terminal-address)]
     (benchmark/measure-fresh-edits!
      {:var #'live/title-version
       :project :ghostty
       :complexity :simple
       :label "fresh title leaf"
       :samples samples
       :edit (fn [declaration context]
               (assoc declaration :body [(fresh-title-version context)]))
       :verify-change
       (fn [context]
         (let [version (fresh-title-version context)]
           (verified-terminal-value "fresh title-version"
                                    version (live/title-version) address)))
       :verify-restore
       #(verified-terminal-value "title-version series restore"
                                 1 (live/title-version) address)}))))

(defn medium-series!
  "Measure fresh converted focus encoders through the existing bridge."
  ([] (medium-series! 5))
  ([samples]
   (medium!)
   (let [encode (requiring-resolve 'ghostty.src.terminal.focus/encode)
         _ (core/focus-final-byte true)
         address (terminal-address)]
     (benchmark/measure-fresh-edits!
      {:var encode
       :project :ghostty
       :complexity :medium
       :label "fresh converted focus encoder"
       :samples samples
       :edit
       (fn [declaration context]
         (let [character (fresh-focus-character context)
               literal (list 'string-literal
                             (str "\"\\x1B[" character "\""))]
           (replace-form
            declaration
            "(string-literal \"\\\"\\\\x1B[I\\\"\")"
            (constantly
             (list 'if
                   (list 'aguafria.keyword/==
                         (:fresh-value context)
                         (:fresh-value context))
                   literal
                   literal)))))
       :verify-change
       (fn [context]
         (let [character (fresh-focus-character context)]
           (verified-terminal-value "fresh focus encoder"
                                    (int character)
                                    (core/focus-final-byte true)
                                    address)))
       :verify-restore
       #(verified-terminal-value "focus encoder series restore"
                                 (int \I) (core/focus-final-byte true) address)}))))

(defn complex-series!
  "Measure fresh real BlockingQueue comptime bodies in one terminal session."
  ([] (complex-series! 5))
  ([samples]
   ;; Establish the generic publication topology before measuring steady-state
   ;; edits. The warm-up performs and verifies its own edit + restore cycle.
   (complex!)
   (let [blocking-queue
         (requiring-resolve 'ghostty.src.datastruct.blocking-queue/BlockingQueue)
         address (terminal-address)]
     (az/await! 'ghostty.src.datastruct.blocking-queue)
     (benchmark/measure-fresh-edits!
      {:var blocking-queue
       :project :ghostty
       :complexity :complex
       :label "fresh BlockingQueue comptime body"
       :samples samples
       :edit
       (fn [declaration context]
         (replace-form
          declaration
          "(== (field self len) bounds)"
          (fn [form]
            (list 'and form
                  (list 'aguafria.keyword/==
                        (:fresh-value context)
                        (:fresh-value context))))))
       :verify-change (fn [_]
                        (verified-terminal "fresh BlockingQueue" address))
       :verify-restore #(verified-terminal "BlockingQueue series restore"
                                           address)}))))

(defn run-distributions!
  "Run fresh simple/medium/complex samples against one terminal instance."
  ([] (run-distributions! 5))
  ([samples]
   {:simple (benchmark/summary (simple-series! samples))
    :medium (benchmark/summary (medium-series! samples))
    :complex (benchmark/summary (complex-series! samples))}))

(defn run-all!
  "Run the three Ghostty cases and return compact EDN summaries."
  []
  {:simple (benchmark/summary (simple!))
   :medium (benchmark/summary (medium!))
   :complex (benchmark/summary (complex!))})
