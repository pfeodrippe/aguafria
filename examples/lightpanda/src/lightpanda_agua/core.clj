(ns lightpanda-agua.core
  "REPL-first host for the Aguafria-generated Lightpanda browser."
  (:require [aguafria.zig :as az]
            [aguafria.zig.host :as host]
            [clojure.pprint :as pprint]
            [lightpanda-agua.generate :as generate]
            [lightpanda-agua.live :as live]
            [lightpanda.src.cdp.id :as lightpanda-id]
            [lightpanda.src.main :as lightpanda-main]))

(defonce development-configuration
  (az/configure! (generate/development-configuration!)))

(def lightpanda-main-module
  "The ordinary Clojure namespace generated from Lightpanda's main.zig."
  'lightpanda.src.main)

(def deterministic-page
  (str "data:text/html,<html><body><main id='answer'>before"
       "<script>document.getElementById('answer').textContent="
       "'Lightpanda-'+(6*7)</script></main></body></html>"))

(defn module-summary
  "Return a compact monitor view for one Aguafria module."
  [module]
  (when-let [info (az/module-info module)]
    {:module (:module info)
     :status (cond
               (:error info) :failed
               (:pending? info) :compiling
               (:source-only? info) :source-only
               (:published-generation info) :finished
               :else :registered)
     :pending? (:pending? info)
     :error (:error info)
     :requested-generation (:requested-generation info)
     :published-generation (:published-generation info)
     :native-generation-count (:native-generation-count info)
     :declaration-count (count (:definitions info))}))

(defn main-var
  "Return Lightpanda's generated main as an ordinary Clojure Var."
  []
  #'lightpanda-main/main)

(defn run-command!
  "Run one finite Lightpanda command on a native thread in this JVM."
  ([arguments] (run-command! arguments {}))
  ([arguments options]
   (host/await!
    (host/start! (main-var)
                 (mapv str arguments)
                 (merge {:argv0 "lightpanda" :share-state? false}
                        options)))))

(defn version!
  "Run the generated Lightpanda version command in this JVM."
  []
  (run-command! ["version"]))

(defn fetch-demo!
  "Run a real browser fetch whose JavaScript changes the dumped DOM to 42."
  []
  (run-command! ["fetch" "--dump" "html" "--wait-until" "load"
                 deterministic-page]))

(defn reset-session!
  "Reset and return the inspectable native development state."
  []
  (live/reset-counter!)
  {:address (live/session-address)
   :counter (live/counter-value)
   :displayed (live/displayed-value)})

(defn advance-session!
  "Advance through Lightpanda's real generated Incrementing type."
  []
  (live/counter-next!)
  {:address (live/session-address)
   :counter (live/counter-value)
   :displayed (live/displayed-value)})

(defn parse-frame-id
  "Call a generated Lightpanda function directly from Clojure."
  [value]
  (let [result (lightpanda-id/parseFrameId value)]
    (try
      (let [decoded (az/value result)]
        (if (and (map? decoded) (contains? decoded :ok))
          (:ok decoded)
          decoded))
      (finally
        (when (az/zig-value? result)
          (az/close! result))))))

(defn await-reload!
  "Wait for selected Lightpanda and example declarations to publish."
  ([] (await-reload! 'lightpanda-agua.live))
  ([module]
   (az/await! module)
   (module-summary module)))

(defn status
  "Return browser, state, compiler, cache, and host statistics."
  []
  {:live {:address (live/session-address)
          :counter (live/counter-value)
          :displayed (live/displayed-value)}
   :main (module-summary lightpanda-main-module)
   :id (module-summary 'lightpanda.src.cdp.id)
   :live-module (module-summary 'lightpanda-agua.live)
   :compiler (:summary (az/stats))
   :hosts (host/stats)})

(defn check!
  "Run direct native declarations plus the real finite browser command."
  []
  {:session (do (reset-session!) (advance-session!))
   :frame-id (parse-frame-id "FID-42")
   :browser (fetch-demo!)
   :status (status)})

(defn -main
  [& [command]]
  (try
    (case command
      "check" (pprint/pprint (check!))
      "version" (pprint/pprint (version!))
      "fetch" (pprint/pprint (fetch-demo!))
      "status" (pprint/pprint (status))
      (println "Use check, version, fetch, or status."))
    (finally
      (shutdown-agents))))

(comment

  ;; ONE-JVM LIGHTPANDA + COMPLEX HOT-RELOAD WALKTHROUGH
  ;;
  ;; From examples/lightpanda, start `clojure -M:nrepl`, connect Calva/CIDER,
  ;; and evaluate this namespace. All native calls below happen in that JVM.

  (reset-session!)
  (advance-session!)
  ;; => address A, counter 1, displayed 101

  ;; The complete generated browser is runnable on a native JVM thread.
  ;; Its JavaScript changes the dumped DOM text from `before` to
  ;; `Lightpanda-42`; watch the nREPL output and inspect the completion map.
  (fetch-demo!)
  ;; => {:status :finished, :exit-code 0, ...}

  ;; HOT EDIT 1 — COMPATIBLE LEAF
  ;; In lightpanda_agua/live.clj change display-offset from 100 to 200 and
  ;; evaluate only that az/defn. No require/reload and no JVM restart:
  (await-reload!)
  (advance-session!)
  ;; => same address A, counter 2, displayed 202

  ;; HOT EDIT 2 — CONVERTED LIGHTPANDA FUNCTION
  ;; In generated/lightpanda/src/cdp/id.clj, change parseFrameId's slice start
  ;; from 4 to 5 and evaluate only that az/defn:
  (await-reload! 'lightpanda.src.cdp.id)
  (parse-frame-id "FID-42")
  ;; => 2. Restore 5 to 4, evaluate once, await, and the result returns to 42.

  ;; HOT EDIT 3 — REAL COMPTIME TYPE FACTORY AND EXISTING NATIVE STATE
  ;; In the generated Incrementing az/defn, find its nested `incr` method and
  ;; change `(ak/+% counter 1)` to `(ak/+% counter 2)`. Evaluate only the full
  ;; Incrementing form, await both modules, then advance:
  (await-reload! 'lightpanda.src.cdp.id)
  (await-reload! 'lightpanda-agua.live)
  (advance-session!)
  ;; => the address remains A and the existing counter now advances by 2.
  ;; Restore +% 1 and reevaluate the same form when finished.

  ;; Automated versions of all three edits restore their original Vars.
  (require '[lightpanda-agua.hot-reload-benchmark :as hot])
  (hot/run-all!)

  (status))
