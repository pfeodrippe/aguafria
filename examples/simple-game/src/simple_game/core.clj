(ns simple-game.core
  "Small REPL dashboard for the running desktop example."
  (:require [aguafria.zig :as az]
            [simple-game.animation :as animation]
            [simple-game.audio :as audio]
            [simple-game.desktop :as desktop]
            [simple-game.font :as font]
            [simple-game.game :as game]
            [simple-game.host :as host]
            [simple-game.physics :as physics]
            [simple-game.vulkan :as vulkan]))

(defn status
  "Return live game, renderer, desktop, and compiler state as ordinary data."
  []
  {:desktop (desktop/desktop-snapshot)
   :game (game/snapshot)
   :animation (animation/snapshot)
   :physics (physics/snapshot)
   :audio (audio/snapshot)
   :fonts (font/snapshot)
   :renderer (vulkan/renderer-snapshot)
   :fps (game/current-fps)
   :frame-timing (host/frame-timing)
   :compilation
   {:game (az/stats 'simple-game.game)
    :renderer (az/stats 'simple-game.vulkan)}})

(comment
  ;; Start the desktop+nREPL JVM from this project:
  ;;   clojure -M:desktop
  ;; Connect Calva/CIDER to the printed port. The game loop and nREPL share
  ;; this one JVM and the one authoritative Flecs world.

  (status)

  ;; Invoke the exact same transition used by the Flecs mouse handler. Both
  ;; paths call game/advance-counter!; this command only locates the live
  ;; Counter component first.
  (game/click!)
  (game/snapshot)

  ;; The generated demo loads from one spritesheet by default. Switch live to
  ;; the equivalent pack built from six separate sprite images, change its
  ;; speed, pause it, or restart it without restarting the JVM or game window.
  (animation/use-generated-sprite-list!)
  (animation/set-fps! 12.0)
  (animation/set-playing! false)
  (animation/restart!)
  (animation/snapshot)

  ;; Edit one az/defn, then evaluate only that top-level form in Calva/CIDER.
  ;; Do not use `(require ... :reload)` for an ordinary edit: that needlessly
  ;; resubmits every declaration in the namespace.
  (az/await! 'simple-game.game)
  (az/await! 'simple-game.vulkan)
  (status)

  ;; Measure real declaration edits in this same JVM. Each scenario verifies
  ;; changed behavior/live state and restores the original Var automatically.
  (require '[simple-game.hot-reload-benchmark :as hot])
  (hot/run-all!)

  ;; Live-edit ABI rules:
  ;;
  ;; - An az/defn body edit with the same parameter/return ABI publishes into
  ;;   the existing dispatch slot; the current Flecs world and entities use it.
  ;; - A new function A can be evaluated first, followed by an existing caller
  ;;   B; B then publishes a generation that calls A without a JVM restart.
  ;; - A signature change creates a new function version. Reevaluate its direct
  ;;   callers (and then their callers) before the new ABI becomes reachable.
  ;; - A defstruct/layout change creates a new type version. Existing native
  ;;   allocations keep their old layout; explicitly migrate/recreate that state
  ;;   before publishing dependents that expect the new layout.

  ;; Build the same sources as one optimized executable, outside the REPL:
  ;;   clojure -M:standalone
  ;;   ./build/standalone/simple-game

  ;; Verify the same pure behavior contract in a headless ReleaseFast artifact:
  ;;   clojure -M:behavior

  ;; Cross-compile the same game/host/scene code to WebAssembly. GLFW owns
  ;; browser input and the main loop; JavaScript only instantiates the module.
  ;;   clojure -M:web
  ;;   clojure -M:serve-web
  ;;   open http://127.0.0.1:8787/
  )
