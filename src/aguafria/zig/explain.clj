(ns aguafria.zig.explain
  "Opt-in reporting of actual compiler and native-cache events."
  (:require [clojure.string :as str]))

(def ^:dynamic *reporter* nil)

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

(defn call-with-report
  "Evaluate once, preserving the result/exception and normal async behavior.
  Reports use the caller's stdout, including events from conveyed bindings."
  [f]
  (let [writer *out*
        counts (atom {})
        report (fn [{:keys [event module function path duration-ms]}]
                 (swap! counts update event (fnil inc 0))
                 (write-report! writer
                                (fn []
                                  (println (str "[aguafria] " (name event) " " (or function module)
                                                (when duration-ms (format " (%.1f ms)" (double duration-ms)))))
                                  (when path (println (str "  " path))))))]
    (binding [*reporter* report]
      (try
        (f)
        (finally
          (write-report! writer
                         (fn []
                           (println (str "[aguafria] "
                                         (if (seq @counts)
                                           (str/join ", " (for [[event n] (sort-by key @counts)]
                                                            (str n " " (name event))))
                                           "no compiler/cache events"))))))))))
