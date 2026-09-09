(ns racing-game.desktop
  "One-window native GLFW/Vulkan host for the twenty-AI race."
  (:refer-clojure :exclude [run!])
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as std-debug]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as az]
            [aguafria-examples-native.bindings]
            [aguafria-examples-native.bindings.glfw :as glfw]
            [aguafria-examples-native.imgui-controls]
            [aguafria-examples-native.bindings.imgui-controls :as ui]
            [aguafria-examples-native.renderer :as renderer]
            [racing-game.assets :as assets]
            [racing-game.inference :as inference]
            [racing-game.motion-qa :as motion-qa]
            [racing-game.render :as race-render]
            [racing-game.render3d :as render3d]
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

(az/defvar live-simulation-slowdown :f64 1.0)

(az/defvar previous-pause false)

(az/defvar previous-reset false)

(az/defvar previous-debug false)

(az/defvar previous-human-toggle false)

(az/defvar previous-item-use false)

(az/defvar previous-camera false)

(az/defvar reset-request :u8 0)

(az/defvar scroll-installed false)

(az/defvar previous-scroll glfw/GLFWscrollfun null)

(az/defn camera-scroll!
  "GLFW wheel callback; ImGui chains this callback when installing its input."
  {:attrs #{:export}}
  :- :void
  [[event-window [:optional [:* glfw/GLFWwindow]]] [horizontal :f64] [vertical :f64]]
  (when (ak/== (ui/aguafria_ui_captures_mouse) 0)
    (render3d/zoom-by! (ak/floatCast (ak/exp (* vertical 0.12)))))
  (when (ak/!= previous-scroll null)
    ((az/unwrap previous-scroll) event-window horizontal vertical)))

(az/defn install-scroll!
  "Install on the window thread, preserving an already installed UI callback."
  :- :void []
  (when (ak/! scroll-installed)
    (set! previous-scroll (glfw/glfwSetScrollCallback window (ak/& camera-scroll!)))
    (set! scroll-installed true)))

(az/defn request-race-reset!
  "Queue a reset for the simulation thread; safe to call from the nREPL."
  :- :void []
  (ak/atomicStore :u8 (ak/& reset-request) 1 :.release))

(az/defn request-stop!
  :-
  :void
  []
  (set! running false))

(az/defn set-live-simulation-slowdown!
  "Run live AI races between real time and 20x slow motion. Rendering remains
  unconstrained, and deterministic replay always advances at normal 120 Hz."
  :-
  :void
  [[factor :f64]]
  (set! live-simulation-slowdown (ak/min 20.0 (ak/max 1.0 factor))))

(az/defn simulation-slowdown
  "Inspect the live wall-time slowdown factor."
  :-
  :f64
  []
  live-simulation-slowdown)

(az/defn poll-control-edges!
  :-
  :void
  []
  ;; GLFW retains a short press until sampled, even when both events arrive
  ;; between rendered frames. This also applies to F2 in the monitor.
  (glfw/glfwSetInputMode window glfw/GLFW_STICKY_KEYS glfw/GLFW_TRUE)
  (install-scroll!)
  (when (ak/!= (ak/atomicRmw :u8 (ak/& reset-request) :.Xchg 0 :.acq_rel) 0)
    (simulation/reset!)
    (render3d/reset-presentation!)
    (render3d/reset-camera!))
  (let [overview (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_F3) glfw/GLFW_PRESS)]
    (when (and overview (ak/! previous-camera))
      (if render3d/follow-camera
        (render3d/camera-preset! 4)
        (render3d/camera-preset! 0)))
    (set! previous-camera overview))
  (when (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_0) glfw/GLFW_PRESS)
    (render3d/follow-leaders!))
  (let [dt (ak/min 0.05 (ak/max 0.0 (- (glfw/glfwGetTime) previous-time)))]
    (when (or (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_EQUAL) glfw/GLFW_PRESS)
              (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_KP_ADD) glfw/GLFW_PRESS))
      (render3d/zoom-by! (ak/floatCast (ak/exp (* dt 2.0)))))
    (when (or (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_MINUS) glfw/GLFW_PRESS)
              (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_KP_SUBTRACT) glfw/GLFW_PRESS))
      (render3d/zoom-by! (ak/floatCast (ak/exp (* dt -2.0))))))
  (dotimes [racer 8]
    (when (ak/== (glfw/glfwGetKey window (+ glfw/GLFW_KEY_1 (ak/as :i32 (ak/intCast racer)))) glfw/GLFW_PRESS)
      (render3d/select-camera! (ak/intCast racer) true)))
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
      (simulation/reset!)
      (render3d/reset-presentation!)
      (render3d/reset-camera!))
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
  "Present one Vulkan frame. Live AI simulation defaults to normal speed;
  optional slow motion remains available for studying model decisions."
  :-
  :bool
  []
  (glfw/glfwPollEvents)
  (poll-control-edges!)
  (let [now (glfw/glfwGetTime)
        elapsed (ak/min 0.10 (ak/max 0.0 (- now previous-time)))]
    (set! previous-time now)
    (set! accumulator (+ accumulator elapsed))
    (let [replay (simulation/replay-summary)
          step-seconds
          (if (az/field replay active)
            0.008333333333
            (* 0.008333333333 live-simulation-slowdown))
          ^{:var true :zig/type :u8} substeps 0]
      (ak/while (and (>= accumulator step-seconds)
                     (< substeps 12))
        (simulation/step!)
        (render3d/capture-presentation!)
        (set! accumulator (- accumulator step-seconds))
        (set! substeps (+ substeps 1)))
      (render3d/set-presentation-phase!
       (if simulation/paused (ak/as :f32 1.0) (ak/floatCast (/ accumulator step-seconds)))))
    (set! frame-count (+ frame-count 1))
    (render3d/advance-camera! (ak/floatCast elapsed))
    (motion-qa/record! now)
    (renderer/render! (ak/& race-render/build-frame!))))

(az/defn window-address
  "Opaque GLFW window address for optional development-only tooling."
  :-
  :u64
  []
  (if (ak/== window null)
    0
    (ak/intCast (ak/intFromPtr (az/unwrap window)))))

(az/defn should-run?
  "Whether the initialized native window should render another frame."
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
                               "Aguafria · 20 Driver AIs · 10 Team Strategist AIs"
                               null null))
  (std-debug/assert (ak/!= window null))
  ;; Present the native game as the active desktop window. Besides making the
  ;; launch predictable for players, this keeps macOS/MoltenVK from starving
  ;; the first CAMetalDrawable while the just-created window is occluded.
  (glfw/glfwFocusWindow window)
  (install-scroll!)
  (std-debug/assert (renderer/initialize-renderer! window))
  (std-debug/assert (worker/start!))
  (simulation/configure-countdown! 0)
  (set! _ (simulation/initialize!))
  (render3d/reset-presentation!)
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
