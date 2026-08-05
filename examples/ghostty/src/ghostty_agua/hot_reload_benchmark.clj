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

(defn run-all!
  "Run the three Ghostty cases and return compact EDN summaries."
  []
  {:simple (benchmark/summary (simple!))
   :medium (benchmark/summary (medium!))
   :complex (benchmark/summary (complex!))})
