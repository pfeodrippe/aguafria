(ns aguafria.zig.explain
  "Opt-in reporting of actual compiler and native-cache events."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def ^:dynamic *reporter* nil)
(def ^:dynamic *verbose*
  "When true, include bundle/memory hits, all form headings and summaries."
  false)

(def ^:private quiet-events
  #{:bundle-cache-hit :bundle-loaded :memory-cache-hit :preparation-cache-hit})

(defn- write-report! [writer f]
  ;; A disconnected REPL output must not replace the wrapped result/exception.
  (try
    (locking writer
      (binding [*out* writer]
        (f)
        (flush)))
    (catch java.io.IOException _ nil)))

(defn event!
  "Record an event only when reporting is enabled. Never requests native work."
  [event]
  (when *reporter* (*reporter* event)))

(defn form-texts
  "Prefer exact editor source carried on the macro call; otherwise pretty-print.
  Source metadata is display-only and is never read or evaluated."
  [macro-form forms]
  (if-let [source (:aguafria.explain/source (meta macro-form))]
    [source]
    (binding [*print-length* nil *print-level* nil *print-meta* false]
      (mapv #(pprint/write % :dispatch pprint/code-dispatch :stream nil :right-margin 80)
            forms))))

(defn call-with-report
  "Evaluate once, preserving the result/exception and normal async behavior.
  Quiet by default: show disk reuse, compilation and failures, with their source.
  Bind *verbose* true for all hits/headings/summaries. Ordinary program output
  is never suppressed. Optional context contains :file, :line and :forms."
  ([f] (call-with-report f nil))
  ([f {:keys [file line forms]}]
   (let [writer *out*
         verbose? *verbose*
         counts (atom {})
         started? (atom false)
         finished? (atom false)
         ;; These helpers run under the writer lock, including conveyed events.
         begin! (fn []
                  (when (and forms (compare-and-set! started? false true))
                    (println)
                    (println (str "[aguafria] >>> BEGIN explain " (or file "<unknown>") ":" (or line "?")))
                    (doseq [form forms] (println form))
                    (println)))
         end! (fn []
                (when (compare-and-set! started? true false)
                  (println "[aguafria] <<< END explain")
                  (println)))
         report (fn [{:keys [event module function path duration-ms]}]
                  (swap! counts update event (fnil inc 0))
                  (when (or verbose? (not (contains? quiet-events event)))
                    (write-report! writer
                                   (fn []
                                     (begin!)
                                     (println (str "[aguafria] " (name event) " " (or function module)
                                                   (when duration-ms (format " (%.1f ms)" (double duration-ms)))))
                                     (when path (println (str "  " path)))
                                     ;; A late async event gets its own complete
                                     ;; source-labelled block, without joining it.
                                     (when @finished? (end!))))))]
     (when verbose? (write-report! writer begin!))
     (binding [*reporter* report]
       (try
         (f)
         (catch Throwable failure
           (write-report! writer begin!)
           (throw failure))
         (finally
           (write-report! writer
                          (fn []
                            (try
                              (when verbose?
                                (println (str "[aguafria] "
                                              (if (seq @counts)
                                                (str/join ", " (for [[event n] (sort-by key @counts)]
                                                                 (str n " " (name event))))
                                                "no compiler/cache events"))))
                              (end!)
                              (finally (reset! finished? true)))))))))))
