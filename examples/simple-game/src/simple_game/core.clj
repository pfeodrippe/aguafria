(ns simple-game.core
  "Small REPL dashboard for the running desktop example."
  (:require [aguafria.zig :as az]
            [simple-game.desktop :as desktop]
            [simple-game.game :as game]
            [simple-game.vulkan :as vulkan]))

(defn status
  "Return live game, renderer, desktop, and compiler state as ordinary data."
  []
  {:desktop (desktop/desktop-snapshot)
   :game (game/snapshot)
   :renderer (vulkan/renderer-snapshot)
   :fps (game/current-fps)
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

  ;; Edit one az/defn, then evaluate only that top-level form in Calva/CIDER.
  ;; Do not use `(require ... :reload)` for an ordinary edit: that needlessly
  ;; resubmits every declaration in the namespace.
  (az/await! 'simple-game.game)
  (az/await! 'simple-game.vulkan)
  (status)

  ;; Build the same sources as one optimized executable, outside the REPL:
  ;;   clojure -M:standalone
  ;;   ./build/standalone/simple-game
  )
