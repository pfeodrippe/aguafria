(ns racing-game.desktop
  "One-window native GLFW/Vulkan host for the eight-AI race."
  (:refer-clojure :exclude [run!])
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as std-debug]
            [aguafria.zig :as az]
            [aguafria-examples-native.bindings]
            [aguafria-examples-native.bindings.glfw :as glfw]
            [aguafria-examples-native.renderer :as renderer]
            [racing-game.inference :as inference]
            [racing-game.render :as race-render]
            [racing-game.simulation :as simulation]
            [racing-game.worker :as worker]))

(az/defstruct DesktopSnapshot
  {:layout :extern}
  [[:running :bool]
   [:frames :u64]
   [:simulation_ticks :u64]
   [:leader :u8]
   [:finished :u8]])

(az/defvar running false)

(az/defvar window [:optional [:* glfw/GLFWwindow]] null)

(az/defvar frame-count :u64 0)

(az/defvar previous-time :f64 0.0)

(az/defvar accumulator :f64 0.0)

(az/defvar previous-pause false)

(az/defvar previous-reset false)

(az/defvar previous-debug false)

(az/defn request-stop!
  :-
  :void
  []
  (set! running false))

(az/defn poll-control-edges!
  {:export false}
  :-
  :void
  []
  (let [pause-down (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_P)
                           glfw/GLFW_PRESS)
        reset-down (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_R)
                           glfw/GLFW_PRESS)
        debug-down (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_D)
                           glfw/GLFW_PRESS)]
    (when (and pause-down (ak/! previous-pause))
      (set! _ (simulation/toggle-paused!)))
    (when (and reset-down (ak/! previous-reset))
      (simulation/reset!))
    (when (and debug-down (ak/! previous-debug))
      (set! _ (race-render/toggle-debug-overlay!)))
    (set! previous-pause pause-down)
    (set! previous-reset reset-down)
    (set! previous-debug debug-down)))

(az/defn frame!
  "Advance an exact 120 Hz simulation accumulator and present one Vulkan frame."
  :-
  :bool
  []
  (glfw/glfwPollEvents)
  (poll-control-edges!)
  (let [now (glfw/glfwGetTime)
        elapsed (ak/min 0.10 (ak/max 0.0 (- now previous-time)))]
    (set! previous-time now)
    (set! accumulator (+ accumulator elapsed))
    (let [^{:var true :zig/type :u8} substeps 0]
      (ak/while (and (>= accumulator 0.008333333333)
                     (< substeps 12))
        (simulation/step!)
        (set! accumulator (- accumulator 0.008333333333))
        (set! substeps (+ substeps 1))))
    (set! frame-count (+ frame-count 1))
    (renderer/render! (ak/& race-render/build-frame!))))

(az/defn run!
  "Run on the JVM first OS thread while the same JVM's nREPL stays live."
  :-
  :bool
  []
  (glfw/glfwInitVulkanLoader glfw/vkGetInstanceProcAddr)
  (std-debug/assert (ak/== (glfw/glfwInit) glfw/GLFW_TRUE))
  (glfw/glfwWindowHint glfw/GLFW_CLIENT_API glfw/GLFW_NO_API)
  (glfw/glfwWindowHint glfw/GLFW_RESIZABLE glfw/GLFW_FALSE)
  (set! window
        (glfw/glfwCreateWindow 1024 720
                               "Aguafria · Eight Independent LLM Racers"
                               null null))
  (std-debug/assert (ak/!= window null))
  (std-debug/assert (renderer/initialize-renderer! window))
  (let [model (inference/load-model!
               "resources/models/granite-4.0-h-350m-Q4_0.gguf")]
    (std-debug/assert (az/field model valid)))
  (let [head
        (inference/load-action-head!
         "resources/models/granite-r2-action-head.f32")]
    (std-debug/assert (az/field head valid)))
  (std-debug/assert (worker/start!))
  (set! _ (simulation/initialize!))
  (set! previous-time (glfw/glfwGetTime))
  (set! accumulator 0.0)
  (set! running true)
  (ak/while (and running
                 (ak/== (glfw/glfwWindowShouldClose window) glfw/GLFW_FALSE))
    (set! _ (frame!)))
  (renderer/shutdown-renderer!)
  (simulation/shutdown!)
  (worker/stop!)
  (inference/unload-model!)
  (glfw/glfwDestroyWindow window)
  (set! window null)
  (glfw/glfwTerminate)
  (set! running false)
  true)

(az/defn desktop-snapshot
  :-
  DesktopSnapshot
  []
  (let [race (simulation/snapshot)]
    (DesktopSnapshot {:running running
                      :frames frame-count
                      :simulation_ticks (az/field race tick)
                      :leader (az/field race leader)
                      :finished (az/field race finished)})))
