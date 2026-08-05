(ns simple-game.core
  "REPL dashboard and live-development examples for Coco House Works."
  (:require [aguafria.zig :as az]
            [simple-game.assets :as assets]
            [simple-game.desktop :as desktop]
            [simple-game.factory :as factory]
            [simple-game.font :as font]
            [simple-game.game :as game]
            [simple-game.host :as host]
            [simple-game.mesh :as mesh]
            [simple-game.vulkan :as vulkan]))

(defn status
  "Return the live factory, renderer, and compiler state as ordinary data."
  []
  {:desktop (desktop/desktop-snapshot)
   :game (game/snapshot)
   :factory (factory/snapshot)
   :assets (assets/snapshot)
   :meshes (mesh/snapshot)
   :fonts (font/snapshot)
   :renderer (vulkan/renderer-snapshot)
   :fps (game/current-fps)
   :frame-timing (host/frame-timing)
   :compilation
   {:factory (az/stats 'simple-game.factory)
    :game (az/stats 'simple-game.game)
    :renderer (az/stats 'simple-game.vulkan)}})

(comment
  ;; Start the game and its nREPL in one JVM:
  ;;   clojure -M:desktop
  ;; Connect Calva/CIDER to the printed port. The nREPL sees the exact Flecs
  ;; world, cells, counters, and native functions used by the open window.

  (status)
  (factory/snapshot)
  (factory/cell-view 15 12)

  ;; Window controls:
  ;;
  ;;   left click   build on the highlighted tile
  ;;   right click  demolish
  ;;   1            belt
  ;;   2            coconut harvester
  ;;   4            coco-panel press
  ;;   6            coco-house construction site
  ;;   R            rotate clockwise
  ;;   P            pause/resume
  ;;   N            explicitly reset the world

  ;; The same operations are ordinary callable Clojure Vars. This places a
  ;; second construction site without restarting the window or copying state.
  (factory/set-build-kind! factory/building-coco-house)
  (factory/place! 18 14 factory/building-coco-house factory/direction-east)
  (factory/cell-view 18 14)
  (factory/remove! 18 14)

  ;; Ordinary development edit: change the 0.80-second press threshold in
  ;; factory/press-duration and evaluate that top-level az/defn directly in
  ;; Calva/CIDER. Watch `:panels_produced` change in the same native world.
  (factory/press-duration)
  (factory/snapshot)

  ;; A compatible mesh/HUD body edit is equally local and preserves all cells.
  (az/await! 'simple-game.mesh)
  (az/await! 'simple-game.hud)
  (status)

  ;; Build the same sources as one JVM-free optimized executable:
  ;;   clojure -M:standalone
  ;;   ./build/standalone/simple-game
  )
