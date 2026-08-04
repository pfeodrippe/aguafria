(ns simple-game.host
  "Shared GLFW input and Flecs frame used by desktop and WebAssembly."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as az]
            [simple-game.bindings.glfw :as glfw]
            [simple-game.game :as game]))

(az/defstruct FrameTiming
  "Native start-to-presentation timing accumulated by the shared frame path."
  {:layout :extern}
  [[:frames :u64]
   [:last_ms :f64]
   [:average_ms :f64]
   [:work_average_ms :f64]
   [:min_ms :f64]
   [:max_ms :f64]
   [:simulation_average_ms :f64]
   [:presentation_average_ms :f64]])

(az/defvar previous-pointer-down false)

(az/defvar pending-pointer-presses :u32 0)

(az/defvar frame-start-seconds :f64 0.0)

(az/defvar current-simulation-seconds :f64 0.0)

(az/defvar measured-frames :u64 0)

(az/defvar timing-sample-count :usize 0)

(az/defvar timing-next-slot :usize 0)

(az/defvar frame-samples [:array 120 :f64]
  (std-mem/zeroes (az/type [:array 120 :f64])))

(az/defvar simulation-samples [:array 120 :f64]
  (std-mem/zeroes (az/type [:array 120 :f64])))

(az/defvar work-samples [:array 120 :f64]
  (std-mem/zeroes (az/type [:array 120 :f64])))

(az/defvar total-frame-seconds :f64 0.0)

(az/defvar total-simulation-seconds :f64 0.0)

(az/defvar total-work-seconds :f64 0.0)

(az/defvar minimum-frame-seconds :f64 0.0)

(az/defvar maximum-frame-seconds :f64 0.0)

(az/defvar last-frame-seconds :f64 0.0)

(az/defn mouse-button-callback
  "Capture GLFW click edges natively so no platform can lose a short press."
  {:attrs #{:public :export}}
  :-
  :void
  [[window [:optional [:* glfw/GLFWwindow]]]
   [button :c_int]
   [action :c_int]
   [mods :c_int]]
  (set! _ window)
  (set! _ mods)
  (when (ak/== button glfw/GLFW_MOUSE_BUTTON_LEFT)
    (when (ak/== action glfw/GLFW_PRESS)
      (set! pending-pointer-presses (+ pending-pointer-presses 1)))
    (set! previous-pointer-down (ak/== action glfw/GLFW_PRESS))))

(az/defn reset-input!
  {:attrs #{:public}}
  :-
  :void
  [[window [:* glfw/GLFWwindow]]]
  (set! _ (glfw/glfwSetMouseButtonCallback window (ak/& mouse-button-callback)))
  (set! previous-pointer-down false)
  (set! pending-pointer-presses 0))

(az/defn reset-frame-timing!
  "Clear full-frame measurements without disturbing game state."
  {:attrs #{:public}}
  :-
  :void
  []
  (set! frame-start-seconds 0.0)
  (set! current-simulation-seconds 0.0)
  (set! measured-frames 0)
  (set! timing-sample-count 0)
  (set! timing-next-slot 0)
  (set! frame-samples (std-mem/zeroes (az/type [:array 120 :f64])))
  (set! simulation-samples (std-mem/zeroes (az/type [:array 120 :f64])))
  (set! work-samples (std-mem/zeroes (az/type [:array 120 :f64])))
  (set! total-frame-seconds 0.0)
  (set! total-simulation-seconds 0.0)
  (set! total-work-seconds 0.0)
  (set! minimum-frame-seconds 0.0)
  (set! maximum-frame-seconds 0.0)
  (set! last-frame-seconds 0.0))

(az/defn finish-frame!
  "Record wall time and CPU work, excluding frame pacing and presentation waits."
  {:attrs #{:public}}
  :-
  :void
  [[render-work-seconds :f64]]
  (when (> frame-start-seconds 0.0)
    (let [elapsed (ak/max 0.0 (- (glfw/glfwGetTime) frame-start-seconds))
          old-frame (az/index frame-samples timing-next-slot)
          old-simulation (az/index simulation-samples timing-next-slot)
          old-work (az/index work-samples timing-next-slot)
          work (+ current-simulation-seconds render-work-seconds)]
      (when (or (ak/== measured-frames 0)
                (< elapsed minimum-frame-seconds))
        (set! minimum-frame-seconds elapsed))
      (when (> elapsed maximum-frame-seconds)
        (set! maximum-frame-seconds elapsed))
      (set! last-frame-seconds elapsed)
      (set! total-frame-seconds (+ (- total-frame-seconds old-frame) elapsed))
      (set! total-simulation-seconds
            (+ (- total-simulation-seconds old-simulation)
               current-simulation-seconds))
      (set! total-work-seconds (+ (- total-work-seconds old-work) work))
      (set! (az/index frame-samples timing-next-slot) elapsed)
      (set! (az/index simulation-samples timing-next-slot)
            current-simulation-seconds)
      (set! (az/index work-samples timing-next-slot) work)
      (set! timing-next-slot (mod (+ timing-next-slot 1) 120))
      (when (< timing-sample-count 120)
        (set! timing-sample-count (+ timing-sample-count 1)))
      (set! measured-frames (+ measured-frames 1))
      (set! frame-start-seconds 0.0))))

(az/defn frame-timing
  "Inspect exact native averages from frame start through presentation."
  :-
  FrameTiming
  []
  (let [count (ak/as :f64 (ak/floatFromInt timing-sample-count))
        average (if (> count 0.0) (/ total-frame-seconds count) 0.0)
        simulation (if (> count 0.0) (/ total-simulation-seconds count) 0.0)
        work (if (> count 0.0) (/ total-work-seconds count) 0.0)]
    (FrameTiming
     {:frames measured-frames
      :last_ms (* last-frame-seconds 1000.0)
      :average_ms (* average 1000.0)
      :work_average_ms (* work 1000.0)
      :min_ms (* minimum-frame-seconds 1000.0)
      :max_ms (* maximum-frame-seconds 1000.0)
      :simulation_average_ms (* simulation 1000.0)
      :presentation_average_ms (* (ak/max 0.0 (- average simulation)) 1000.0)})))

(az/defn frame!
  "Poll GLFW and advance the single shared Flecs game by one platform-timed frame."
  {:attrs #{:public :implicit-return}}
  :-
  game/RenderPacket
  [[window [:* glfw/GLFWwindow]]]
  (set! frame-start-seconds (glfw/glfwGetTime))
  (glfw/glfwPollEvents)
  (let [^{:var true :zig/type :f64} cursor-x 0.0
        ^{:var true :zig/type :f64} cursor-y 0.0
        ^{:var true :zig/type :i32} window-width 0
        ^{:var true :zig/type :i32} window-height 0
        ^{:var true :zig/type :i32} frame-width 0
        ^{:var true :zig/type :i32} frame-height 0]
    (glfw/glfwGetCursorPos window (ak/& cursor-x) (ak/& cursor-y))
    (glfw/glfwGetWindowSize window (ak/& window-width) (ak/& window-height))
    (glfw/glfwGetFramebufferSize window (ak/& frame-width) (ak/& frame-height))
    (let [pointer-x (if (> window-width 0)
                      (* (ak/as :f32 (ak/floatCast cursor-x))
                         (/ 720.0 (ak/as :f32 (ak/floatFromInt window-width))))
                      -1.0)
          pointer-y (if (> window-height 0)
                      (* (ak/as :f32 (ak/floatCast cursor-y))
                         (/ 540.0 (ak/as :f32 (ak/floatFromInt window-height))))
                      -1.0)
          input-work (ak/max 0.0 (- (glfw/glfwGetTime) frame-start-seconds))
          packet (game/tick-auto pointer-x pointer-y 720.0 540.0
                                 previous-pointer-down (> pending-pointer-presses 0))]
      (set! current-simulation-seconds
            (+ input-work (game/last-frame-work-seconds)))
      (when (> pending-pointer-presses 0)
        (set! pending-pointer-presses (- pending-pointer-presses 1)))
      packet)))
