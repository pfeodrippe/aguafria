(ns racing-game.desktop
  "One-window native GLFW/Vulkan host for the twenty-AI race."
  (:refer-clojure :only [])
  (:require [aguafria.std]
            [aguafria.keyword :as ak :refer :all]
            [aguafria.std.debug :as std-debug]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as a]
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

(a/defstruct DesktopSnapshot
  {:layout :extern}
  [[:running :bool]
   [:frames :u64]
   [:simulation_ticks :u64]
   [:leader :u8]
   [:finished :u8]])

(a/defvar running false)

(a/defvar window [:optional [:* glfw/GLFWwindow]] null)

(a/defvar frame-count :u64 0)

(a/defvar previous-time :f64 0.0)

(a/defvar accumulator :f64 0.0)

(a/defvar live-simulation-slowdown :f64 1.0)

(a/defvar previous-pause false)

(a/defvar previous-reset false)

(a/defvar previous-debug false)

(a/defvar previous-human-toggle false)

(a/defvar previous-item-use false)

(a/defvar previous-camera false)

(a/defvar reset-request :u8 0)

(a/defvar scroll-installed false)

(a/defvar previous-scroll glfw/GLFWscrollfun null)

(a/defn camera-scroll! :void
  "GLFW wheel callback; ImGui chains this callback when installing its input."
  {:attrs #{:export}}
  [[event-window [:optional [:* glfw/GLFWwindow]]] [horizontal :f64] [vertical :f64]]
  (when (== (ui/aguafria_ui_captures_mouse) 0)
    (render3d/zoom-by! (floatCast (exp (* vertical 0.12)))))
  (when (!= previous-scroll null)
    ((a/unwrap previous-scroll) event-window horizontal vertical)))

(a/defn install-scroll! :void
  "Install on the window thread, preserving an already installed UI callback." []
  (when (! scroll-installed)
    (= previous-scroll (glfw/glfwSetScrollCallback window (ak/& camera-scroll!)))
    (= scroll-installed true)))

(a/defn request-race-reset! :void
  "Queue a reset for the simulation thread; safe to call from the nREPL." []
  (atomicStore :u8 (ak/& reset-request) 1 :release))

(a/defn request-stop! :void
  []
  (= running false))

(a/defn set-live-simulation-slowdown! :void
  "Run live AI races between real time and 20x slow motion. Rendering remains
  unconstrained, and deterministic replay always advances at normal 120 Hz."
  [[factor :f64]]
  (= live-simulation-slowdown (min 20.0 (max 1.0 factor))))

(a/defn simulation-slowdown :f64
  "Inspect the live wall-time slowdown factor."
  []
  live-simulation-slowdown)

(a/defn poll-control-edges! :void
  []
  ;; GLFW retains a short press until sampled, even when both events arrive
  ;; between rendered frames. This also applies to F2 in the monitor.
  (glfw/glfwSetInputMode window glfw/GLFW_STICKY_KEYS glfw/GLFW_TRUE)
  (install-scroll!)
  (when (!= (atomicRmw :u8 (ak/& reset-request) :Xchg 0 :acq_rel) 0)
    (simulation/reset!)
    (render3d/reset-presentation!)
    (render3d/reset-camera!))
  (let [overview (== (glfw/glfwGetKey window glfw/GLFW_KEY_F3) glfw/GLFW_PRESS)]
    (when (and overview (! previous-camera))
      (if render3d/follow-camera
        (render3d/camera-preset! 4)
        (render3d/camera-preset! 0)))
    (= previous-camera overview))
  (when (== (glfw/glfwGetKey window glfw/GLFW_KEY_0) glfw/GLFW_PRESS)
    (render3d/follow-leaders!))
  (let [dt (min 0.05 (max 0.0 (- (glfw/glfwGetTime) previous-time)))]
    (when (or (== (glfw/glfwGetKey window glfw/GLFW_KEY_EQUAL) glfw/GLFW_PRESS)
              (== (glfw/glfwGetKey window glfw/GLFW_KEY_KP_ADD) glfw/GLFW_PRESS))
      (render3d/zoom-by! (floatCast (exp (* dt 2.0)))))
    (when (or (== (glfw/glfwGetKey window glfw/GLFW_KEY_MINUS) glfw/GLFW_PRESS)
              (== (glfw/glfwGetKey window glfw/GLFW_KEY_KP_SUBTRACT) glfw/GLFW_PRESS))
      (render3d/zoom-by! (floatCast (exp (* dt -2.0))))))
  (dotimes [racer 8]
    (when (== (glfw/glfwGetKey window (+ glfw/GLFW_KEY_1 (as (intCast racer) :i32))) glfw/GLFW_PRESS)
      (render3d/select-camera! (intCast racer) true)))
  (let [pause-down (== (glfw/glfwGetKey window glfw/GLFW_KEY_P)
                       glfw/GLFW_PRESS)
        reset-down (== (glfw/glfwGetKey window glfw/GLFW_KEY_R)
                       glfw/GLFW_PRESS)
        debug-down (== (glfw/glfwGetKey window glfw/GLFW_KEY_F1)
                       glfw/GLFW_PRESS)
        human-toggle-down
        (== (glfw/glfwGetKey window glfw/GLFW_KEY_H) glfw/GLFW_PRESS)
        left-down
        (or (== (glfw/glfwGetKey window glfw/GLFW_KEY_LEFT)
                glfw/GLFW_PRESS)
            (== (glfw/glfwGetKey window glfw/GLFW_KEY_A)
                glfw/GLFW_PRESS))
        right-down
        (or (== (glfw/glfwGetKey window glfw/GLFW_KEY_RIGHT)
                glfw/GLFW_PRESS)
            (== (glfw/glfwGetKey window glfw/GLFW_KEY_D)
                glfw/GLFW_PRESS))
        throttle-down
        (or (== (glfw/glfwGetKey window glfw/GLFW_KEY_UP)
                glfw/GLFW_PRESS)
            (== (glfw/glfwGetKey window glfw/GLFW_KEY_W)
                glfw/GLFW_PRESS))
        brake-down
        (or (== (glfw/glfwGetKey window glfw/GLFW_KEY_DOWN)
                glfw/GLFW_PRESS)
            (== (glfw/glfwGetKey window glfw/GLFW_KEY_S)
                glfw/GLFW_PRESS))
        keyboard-use
        (== (glfw/glfwGetKey window glfw/GLFW_KEY_SPACE) glfw/GLFW_PRESS)
        ^{:var true}
        gamepad (std-mem/zeroes (a/type glfw/GLFWgamepadstate))
        gamepad-connected
        (== (glfw/glfwGetGamepadState glfw/GLFW_JOYSTICK_1
                                      (ak/& gamepad))
            glfw/GLFW_TRUE)
        gamepad-steering
        (if gamepad-connected
          (a/index (a/field gamepad axes) glfw/GLFW_GAMEPAD_AXIS_LEFT_X)
          0.0)
        gamepad-throttle
        (if gamepad-connected
          (* (+ (a/index (a/field gamepad axes)
                         glfw/GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER)
                1.0)
             0.5)
          0.0)
        gamepad-brake
        (if gamepad-connected
          (* (+ (a/index (a/field gamepad axes)
                         glfw/GLFW_GAMEPAD_AXIS_LEFT_TRIGGER)
                1.0)
             0.5)
          0.0)
        gamepad-use
        (and gamepad-connected
             (== (a/index (a/field gamepad buttons)
                          glfw/GLFW_GAMEPAD_BUTTON_A)
                 glfw/GLFW_PRESS))
        item-use (or keyboard-use gamepad-use)
        keyboard-steering
        (f32 (cond
               (and left-down (! right-down)) -1.0
               (and right-down (! left-down)) 1.0
               :else 0.0))
        steering
        (f32 (if (> (abs gamepad-steering) 0.15)
               gamepad-steering
               keyboard-steering))
        throttle
        (f32 (max (if throttle-down (as 1.0 :f32) (as 0.0 :f32))
                  gamepad-throttle))
        brake
        (f32 (max (if brake-down (as 1.0 :f32) (as 0.0 :f32))
                  gamepad-brake))]
    (when (and pause-down (! previous-pause))
      (= :_ (simulation/toggle-paused!)))
    (when (and reset-down (! previous-reset))
      (simulation/reset!)
      (render3d/reset-presentation!)
      (render3d/reset-camera!))
    (when (and debug-down (! previous-debug))
      (= :_ (race-render/toggle-debug-overlay!)))
    (when (and human-toggle-down (! previous-human-toggle))
      (= :_
         (simulation/set-human-controlled!
          (! (a/field (simulation/human-control-snapshot) enabled)))))
    (simulation/set-human-input!
     steering throttle brake (and item-use (! previous-item-use)))
    (= previous-pause pause-down)
    (= previous-reset reset-down)
    (= previous-debug debug-down)
    (= previous-human-toggle human-toggle-down)
    (= previous-item-use item-use)))

(a/defn frame! :bool
  "Present one Vulkan frame. Live AI simulation defaults to normal speed;
  optional slow motion remains available for studying model decisions."
  []
  (glfw/glfwPollEvents)
  (poll-control-edges!)
  (let [now (glfw/glfwGetTime)
        elapsed (min 0.10 (max 0.0 (- now previous-time)))]
    (= previous-time now)
    (= accumulator (+ accumulator elapsed))
    (let [replay (simulation/replay-summary)
          step-seconds
          (if (a/field replay active)
            0.008333333333
            (* 0.008333333333 live-simulation-slowdown))
          ^:var substeps (u8 0)]
      (while (and (>= accumulator step-seconds)
                  (< substeps 12))
        (simulation/step!)
        (render3d/capture-presentation!)
        (= accumulator (- accumulator step-seconds))
        (= substeps (+ substeps 1)))
      (render3d/set-presentation-phase!
       (if simulation/paused (as 1.0 :f32) (floatCast (/ accumulator step-seconds)))))
    (= frame-count (+ frame-count 1))
    (render3d/advance-camera! (floatCast elapsed))
    (motion-qa/record! now)
    (renderer/render! (ak/& race-render/build-frame!))))

(a/defn window-address :u64
  "Opaque GLFW window address for optional development-only tooling."
  []
  (if (== window null)
    0
    (intCast (intFromPtr (a/unwrap window)))))

(a/defn should-run? :bool
  "Whether the initialized native window should render another frame."
  []
  (and running
       (!= window null)
       (== (glfw/glfwWindowShouldClose window) glfw/GLFW_FALSE)))

(a/defn initialize! :bool
  "Create the window, renderer, workers, and Flecs race without entering a loop."
  []
  (when running
    (return true))
  (when (! (assets/load-and-verify!))
    (return false))
  (glfw/glfwInitVulkanLoader glfw/vkGetInstanceProcAddr)
  (std-debug/assert (== (glfw/glfwInit) glfw/GLFW_TRUE))
  (glfw/glfwWindowHint glfw/GLFW_CLIENT_API glfw/GLFW_NO_API)
  (glfw/glfwWindowHint glfw/GLFW_RESIZABLE glfw/GLFW_FALSE)
  (= window
     (glfw/glfwCreateWindow 1024 720
                            "Aguafria · 20 Driver AIs · 10 Team Strategist AIs"
                            null null))
  (std-debug/assert (!= window null))
  ;; Present the native game as the active desktop window. Besides making the
  ;; launch predictable for players, this keeps macOS/MoltenVK from starving
  ;; the first CAMetalDrawable while the just-created window is occluded.
  (glfw/glfwFocusWindow window)
  (install-scroll!)
  (std-debug/assert (renderer/initialize-renderer! window))
  (std-debug/assert (worker/start!))
  (simulation/configure-countdown! 0)
  (= :_ (simulation/initialize!))
  (render3d/reset-presentation!)
  (= previous-time (glfw/glfwGetTime))
  (= accumulator 0.0)
  (= running true)
  true)

(a/defn shutdown! :void
  "Destroy the resources owned by `initialize!`. Safe after a normal loop."
  []
  (when (!= window null)
    (= running false)
    (renderer/shutdown-renderer!)
    (simulation/shutdown!)
    (worker/stop!)
    (inference/unload-model!)
    (glfw/glfwDestroyWindow window)
    (= window null)
    (glfw/glfwTerminate)))

(a/defn run! :bool
  "Run on the JVM first OS thread while the same JVM's nREPL stays live."
  []
  (when (! (initialize!))
    (return false))
  (while (should-run?)
    (= :_ (frame!)))
  (shutdown!)
  true)

(a/defn desktop-snapshot DesktopSnapshot
  []
  (let [race (simulation/snapshot)]
    (DesktopSnapshot {:running running
                      :frames frame-count
                      :simulation_ticks (a/field race tick)
                      :leader (a/field race leader)
                      :finished (a/field race finished)})))
