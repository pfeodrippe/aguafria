(ns simple-game.host
  "Shared GLFW input and Flecs frame used by desktop and WebAssembly."
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]
            [simple-game.bindings.glfw :as glfw]
            [simple-game.game :as game]))

(az/defvar previous-pointer-down false)

(az/defvar pending-pointer-presses :u32 0)

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

(az/defn frame!
  "Poll GLFW and advance the single shared Flecs game by one platform-timed frame."
  {:attrs #{:public :implicit-return}}
  :-
  game/RenderPacket
  [[window [:* glfw/GLFWwindow]]]
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
          packet (game/tick-auto pointer-x pointer-y 720.0 540.0
                                 previous-pointer-down (> pending-pointer-presses 0))]
      (when (> pending-pointer-presses 0)
        (set! pending-pointer-presses (- pending-pointer-presses 1)))
      packet)))
