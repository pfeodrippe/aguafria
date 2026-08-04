(ns ghostty-agua.core
  "REPL-first host for the Aguafria-generated Ghostty terminal core."
  (:require [aguafria.zig :as az]
            [clojure.pprint :as pprint]
            [ghostty-agua.live :as live]
            [ghostty-agua.native :as native]))

(def ghostty-focus-module
  "A representative generated Ghostty module with directly callable Vars."
  'ghostty.src.terminal.c.focus)

(defonce ^:private current-session
  (atom nil))

(declare state)

(defn session
  "Return the current same-JVM Ghostty terminal, if one is open."
  []
  @current-session)

(defn start!
  "Open a real regenerated libghostty-vt terminal in this JVM.

  An existing session is returned unchanged unless `:replace? true` is set."
  ([] (start! {}))
  ([{:keys [replace?] :as options}]
   (if-let [existing @current-session]
     (if replace?
       (do
         (native/close! existing)
         (let [opened (native/open! (dissoc options :replace?))]
           (reset! current-session opened)
           opened))
       existing)
     (let [opened (native/open! (dissoc options :replace?))]
       (reset! current-session opened)
       opened))))

(defn stop!
  "Close the current native terminal."
  []
  (when-let [opened (swap! current-session (constantly nil))]
    (native/close! opened))
  nil)

(defn write!
  "Feed UTF-8 text or bytes through the current terminal's VT parser."
  [value]
  (native/write! (or (session) (start!)) value)
  (state))

(defn resize!
  "Resize the current live terminal."
  ([cols rows]
   (resize! cols rows 8 16))
  ([cols rows cell-width-px cell-height-px]
   (native/resize! (or (session) (start!))
                   cols rows cell-width-px cell-height-px)
   (state)))

(defn state
  "Return a plain Clojure map of the current native terminal state."
  []
  (when-let [opened (session)]
    (native/state opened)))

(defn type-layout-json
  "Return Ghostty's own target-specific C ABI layout description."
  []
  (native/type-layout-json (or (session) (start!))))

(defn focus-final-byte
  "Call generated Ghostty focus encoding through a JVM-shaped Zig wrapper."
  [gained?]
  ((requiring-resolve 'ghostty-agua.bridge/focus-final-byte) gained?))

(defn publish-hot-title!
  "Use the latest native Aguafria generation to update the existing terminal.

  Reevaluate only `live-title-version`, call this again, and observe that the
  terminal handle and all other state remain unchanged while the title uses
  the new implementation."
  []
  (let [opened (or (session) (start!))
        address (.address ^java.lang.foreign.MemorySegment (:terminal opened))]
    (native/write! opened
                   (str "\u001b]2;Aguafria Ghostty hot generation "
                        (live/title-version)
                        "\u0007"))
    {:terminal-address address
     :title (:title (native/state opened))
     :native-version (live/title-version)}))

(defn await-reload!
  "Wait for the latest declaration publications in the example module."
  []
  (az/await! 'ghostty-agua.live)
  (select-keys (az/module-info 'ghostty-agua.live)
               [:module :pending? :error :requested-generation
                :published-generation :native-generation-count]))

(defn status
  "Return monitor-ready compiler and terminal statistics."
  []
  {:terminal (when-let [opened (session)]
               {:address (.address
                          ^java.lang.foreign.MemorySegment (:terminal opened))
                :state (native/state opened)})
   :example-module (select-keys (az/module-info 'ghostty-agua.live)
                                [:module :pending? :error
                                 :requested-generation :published-generation
                                 :native-generation-count])
   :ghostty-module (select-keys (az/module-info ghostty-focus-module)
                                [:module :pending? :error
                                 :requested-generation :published-generation
                                 :native-generation-count])
   :compiler (:summary (az/stats))})

(defn check!
  "Exercise a real VT session without starting another process."
  []
  (let [opened (start! {:replace? true})
        address (.address ^java.lang.foreign.MemorySegment (:terminal opened))]
    (native/write! opened
                   "Ghostty from Clojure\r\n\u001b[31mred\u001b[0m\u001b]2;Aguafria Ghostty\u0007")
    {:terminal-address address
     :state (native/state opened)
     :layout-json-prefix (subs (native/type-layout-json opened) 0 80)}))

(defn- usage
  []
  (str "Ghostty Aguafria example\n\n"
       "  clojure -M:check\n"
       "  clojure -M:nrepl\n"
       "  clojure -M:generate\n"
       "  clojure -M:standalone\n"
       "  clojure -M:macos-app\n"))

(defn -main
  [& [command]]
  (try
    (case command
      "check" (pprint/pprint (check!))
      "status" (pprint/pprint (status))
      (println (usage)))
    (finally
      (stop!)
      (shutdown-agents))))

(comment

  ;; ONE-JVM GHOSTTY + HOT-RELOAD WALKTHROUGH
  ;;
  ;; From examples/ghostty, start `clojure -M:nrepl`, connect Calva/CIDER to
  ;; the printed port, and evaluate this namespace once. No helper JVM or
  ;; Ghostty process is created in any step below.

  (def terminal (start!))
  terminal
  ;; => #ghostty/session {:address ..., :closed? false, ...}

  (write! "Bonjour from a real Ghostty VT parser\r\n")
  (write! "\u001b[32mANSI green\u001b[0m\r\n")
  (resize! 100 30 9 18)
  (state)
  ;; Inspect :cursor-x, :cursor-y, :cols, :rows, :title, scrollback, etc.

  ;; A generated Ghostty declaration is an ordinary directly callable Var.
  ;; Its first invocation compiles and caches the minimal dependency component.
  (require '[ghostty-agua.bridge :as bridge])
  (bridge/focus-final-byte true)
  (focus-final-byte false)
  ;; => 73 (`I`) for focus gained, 79 (`O`) for focus lost.

  ;; HOT EDIT 1: compatible function body, existing native state retained.
  (publish-hot-title!)
  ;; => {:terminal-address A, :title "... generation 1", :native-version 1}
  ;;
  ;; Open `src/ghostty_agua/live.clj`. Change only the final `1` in
  ;; live/title-version to `2`, evaluate that ONE az/defn, and then evaluate:
  (await-reload!)
  (publish-hot-title!)
  ;; => the same :terminal-address A and all terminal contents are retained,
  ;;    while :title and :native-version now end in 2.

  ;; HOT EDIT 2: new function A, then existing function B starts using it.
  ;; In ghostty_agua/live.clj, add and evaluate this new declaration A:
  ;;   (az/defn title-offset :- :u32 [] 40)
  ;; Then change existing title-version (B)'s final expression to:
  ;;   (+ (title-offset) 2)
  ;; Evaluate only live-title-version and publish again:
  (await-reload!)
  (publish-hot-title!)
  ;; => same session, generation 42. The newly declared native function is now
  ;;    reached through the republished existing function.

  ;; HOT EDIT 3: edit converted Ghostty itself.
  ;; Open generated/ghostty/src/terminal/focus.clj, find its
  ;; `az/defn encode`, change the gained sequence's final `I` byte to `X`, and
  ;; evaluate only that form. Await that generated module and the bridge:
  (az/await! 'ghostty.src.terminal.focus)
  (az/await! 'ghostty-agua.bridge)
  (focus-final-byte true)
  ;; => 88 (`X`). Restore/evaluate the form and it returns to 73. The
  ;;    upstream value. The terminal above stays open across both publications.

  (status)

  ;; Measure a leaf, converted cross-namespace function, and real comptime
  ;; type factory in this same JVM; every scenario restores its original Var.
  (require '[ghostty-agua.hot-reload-benchmark :as hot])
  (hot/run-all!)

  (stop!))
