(ns racing-game.desktop
  "One-window native GLFW/Vulkan host for the eight-AI race."
  (:refer-clojure :exclude [run!])
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as std-debug]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as az]
            [aguafria-examples-native.bindings]
            [aguafria-examples-native.bindings.glfw :as glfw]
            [aguafria-examples-native.renderer :as renderer]
            [racing-game.assets :as assets]
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

(az/defvar previous-human-toggle false)

(az/defvar previous-item-use false)

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
        debug-down (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_F1)
                          glfw/GLFW_PRESS)
        human-toggle-down
        (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_H) glfw/GLFW_PRESS)
        left-down
        (or (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_LEFT)
                   glfw/GLFW_PRESS)
            (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_A)
                   glfw/GLFW_PRESS))
        right-down
        (or (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_RIGHT)
                   glfw/GLFW_PRESS)
            (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_D)
                   glfw/GLFW_PRESS))
        throttle-down
        (or (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_UP)
                   glfw/GLFW_PRESS)
            (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_W)
                   glfw/GLFW_PRESS))
        brake-down
        (or (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_DOWN)
                   glfw/GLFW_PRESS)
            (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_S)
                   glfw/GLFW_PRESS))
        keyboard-use
        (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_SPACE) glfw/GLFW_PRESS)
        ^{:var true}
        gamepad (std-mem/zeroes (az/type glfw/GLFWgamepadstate))
        gamepad-connected
        (ak/== (glfw/glfwGetGamepadState glfw/GLFW_JOYSTICK_1
                                         (ak/& gamepad))
               glfw/GLFW_TRUE)
        gamepad-steering
        (if gamepad-connected
          (az/index (az/field gamepad axes) glfw/GLFW_GAMEPAD_AXIS_LEFT_X)
          0.0)
        gamepad-throttle
        (if gamepad-connected
          (* (+ (az/index (az/field gamepad axes)
                          glfw/GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER)
                1.0)
             0.5)
          0.0)
        gamepad-brake
        (if gamepad-connected
          (* (+ (az/index (az/field gamepad axes)
                          glfw/GLFW_GAMEPAD_AXIS_LEFT_TRIGGER)
                1.0)
             0.5)
          0.0)
        gamepad-use
        (and gamepad-connected
             (ak/== (az/index (az/field gamepad buttons)
                              glfw/GLFW_GAMEPAD_BUTTON_A)
                    glfw/GLFW_PRESS))
        item-use (or keyboard-use gamepad-use)
        ^{:zig/type :f32}
        keyboard-steering
        (cond
          (and left-down (ak/! right-down)) -1.0
          (and right-down (ak/! left-down)) 1.0
          :else 0.0)
        ^{:zig/type :f32}
        steering
        (if (> (ak/abs gamepad-steering) 0.15)
          gamepad-steering
          keyboard-steering)
        ^{:zig/type :f32}
        throttle
        (ak/max (if throttle-down (ak/as :f32 1.0) (ak/as :f32 0.0))
                gamepad-throttle)
        ^{:zig/type :f32}
        brake
        (ak/max (if brake-down (ak/as :f32 1.0) (ak/as :f32 0.0))
                gamepad-brake)]
    (when (and pause-down (ak/! previous-pause))
      (set! _ (simulation/toggle-paused!)))
    (when (and reset-down (ak/! previous-reset))
      (simulation/reset!))
    (when (and debug-down (ak/! previous-debug))
      (set! _ (race-render/toggle-debug-overlay!)))
    (when (and human-toggle-down (ak/! previous-human-toggle))
      (set! _
            (simulation/set-human-controlled!
             (ak/! (az/field (simulation/human-control-snapshot) enabled)))))
    (simulation/set-human-input!
     steering throttle brake (and item-use (ak/! previous-item-use)))
    (set! previous-pause pause-down)
    (set! previous-reset reset-down)
    (set! previous-debug debug-down)
    (set! previous-human-toggle human-toggle-down)
    (set! previous-item-use item-use)))

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

(az/defn window-address
  "Opaque GLFW window address for optional development-only tooling."
  {:attrs #{:public :implicit-return}}
  :-
  :u64
  []
  (if (ak/== window null)
    0
    (ak/intCast (ak/intFromPtr (az/unwrap window)))))

(az/defn should-run?
  "Whether the initialized native window should render another frame."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  []
  (and running
       (ak/!= window null)
       (ak/== (glfw/glfwWindowShouldClose window) glfw/GLFW_FALSE)))

(az/defn initialize!
  "Create the window, renderer, workers, and Flecs race without entering a loop."
  :-
  :bool
  []
  (when running
    (ak/return true))
  (when (ak/! (assets/load-and-verify!))
    (ak/return false))
  (glfw/glfwInitVulkanLoader glfw/vkGetInstanceProcAddr)
  (std-debug/assert (ak/== (glfw/glfwInit) glfw/GLFW_TRUE))
  (glfw/glfwWindowHint glfw/GLFW_CLIENT_API glfw/GLFW_NO_API)
  (glfw/glfwWindowHint glfw/GLFW_RESIZABLE glfw/GLFW_FALSE)
  (set! window
        (glfw/glfwCreateWindow 1024 720
                               "Aguafria · 8 Driver AIs · 4 Team Strategist AIs"
                               null null))
  (std-debug/assert (ak/!= window null))
  ;; Present the native game as the active desktop window. Besides making the
  ;; launch predictable for players, this keeps macOS/MoltenVK from starving
  ;; the first CAMetalDrawable while the just-created window is occluded.
  (glfw/glfwFocusWindow window)
  (std-debug/assert (renderer/initialize-renderer! window))
  (std-debug/assert (worker/start!))
  (simulation/configure-countdown! 0)
  (set! _ (simulation/initialize!))
  (set! previous-time (glfw/glfwGetTime))
  (set! accumulator 0.0)
  (set! running true)
  true)

(az/defn shutdown!
  "Destroy the resources owned by `initialize!`. Safe after a normal loop."
  :-
  :void
  []
  (when (ak/!= window null)
    (set! running false)
    (renderer/shutdown-renderer!)
    (simulation/shutdown!)
    (worker/stop!)
    (inference/unload-model!)
    (glfw/glfwDestroyWindow window)
    (set! window null)
    (glfw/glfwTerminate)))

(az/defn run!
  "Run on the JVM first OS thread while the same JVM's nREPL stays live."
  :-
  :bool
  []
  (when (ak/! (initialize!))
    (ak/return false))
  (ak/while (should-run?)
    (set! _ (frame!)))
  (shutdown!)
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
