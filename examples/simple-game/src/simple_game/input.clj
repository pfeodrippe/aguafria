(ns simple-game.input
  "Platform-neutral actions derived from GLFW on desktop and WebAssembly."
  (:refer-clojure :exclude [reset!])
  (:require [aguafria.keyword :as ak]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as az]
            [simple-game.bindings.glfw :as glfw]))

(az/defstruct InputSnapshot
  "One normalized input frame consumed by gameplay and recording."
  {:layout :extern}
  [[:pointer_x :f32]
   [:pointer_y :f32]
   [:pointer_down :bool]
   [:activate_pressed :bool]
   [:secondary_down :bool]
   [:pause_pressed :bool]
   [:restart_pressed :bool]
   [:rotate_pressed :bool]
   [:inspector_pressed :bool]
   [:build_selection :u8]
   [:move_x :f32]
   [:move_y :f32]
   [:gamepad_connected :bool]])

(az/defvar previous-pause false)

(az/defvar previous-restart false)

(az/defvar previous-rotate false)

(az/defvar previous-inspector false)

(az/defvar previous-gamepad-activate false)

(az/defvar current InputSnapshot
  (InputSnapshot {:pointer_x -1.0
                  :pointer_y -1.0
                  :pointer_down false
                  :activate_pressed false
                  :secondary_down false
                  :pause_pressed false
                  :restart_pressed false
                  :rotate_pressed false
                  :inspector_pressed false
                  :build_selection 0
                  :move_x 0.0
                  :move_y 0.0
                  :gamepad_connected false}))

(az/defn pressed-edge?
  "Pure rising-edge rule shared by keyboard and gamepad actions."
  :-
  :bool
  [[down :bool]
   [previous-down :bool]]
  (and down (ak/! previous-down)))

(az/defn reset!
  :-
  :void
  []
  (set! previous-pause false)
  (set! previous-restart false)
  (set! previous-rotate false)
  (set! previous-inspector false)
  (set! previous-gamepad-activate false)
  (set! current
        (InputSnapshot {:pointer_x -1.0
                        :pointer_y -1.0
                        :pointer_down false
                        :activate_pressed false
                        :secondary_down false
                        :pause_pressed false
                        :restart_pressed false
                        :rotate_pressed false
                        :inspector_pressed false
                        :build_selection 0
                        :move_x 0.0
                        :move_y 0.0
                        :gamepad_connected false})))

(az/defn poll!
  "Translate GLFW pointer, keyboard, and first-gamepad state into actions."
  :-
  InputSnapshot
  [[window [:* glfw/GLFWwindow]]
   [pointer-x :f32]
   [pointer-y :f32]
   [pointer-down :bool]
   [pointer-pressed :bool]]
  (let [pause-down (or (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_P)
                              glfw/GLFW_PRESS)
                       (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_SPACE)
                              glfw/GLFW_PRESS))
        restart-down (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_N)
                            glfw/GLFW_PRESS)
        rotate-down (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_R)
                           glfw/GLFW_PRESS)
        inspector-down (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_I)
                              glfw/GLFW_PRESS)
        secondary-down
        (ak/== (glfw/glfwGetMouseButton window glfw/GLFW_MOUSE_BUTTON_RIGHT)
               glfw/GLFW_PRESS)
        ^{:zig/type :u8}
        build-selection
        (cond
          (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_1) glfw/GLFW_PRESS) 1
          (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_2) glfw/GLFW_PRESS) 2
          (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_3) glfw/GLFW_PRESS) 3
          (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_4) glfw/GLFW_PRESS) 4
          (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_5) glfw/GLFW_PRESS) 5
          (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_6) glfw/GLFW_PRESS) 6
          :else 0)
        ^{:var true}
        gamepad (std-mem/zeroes (az/type glfw/GLFWgamepadstate))
        gamepad-connected
        (ak/== (glfw/glfwGetGamepadState glfw/GLFW_JOYSTICK_1
                                         (ak/& gamepad))
               glfw/GLFW_TRUE)
        gamepad-activate
        (and gamepad-connected
             (ak/== (az/index (az/field gamepad buttons)
                              glfw/GLFW_GAMEPAD_BUTTON_A)
                    glfw/GLFW_PRESS))
        move-x (if gamepad-connected
                 (az/index (az/field gamepad axes)
                           glfw/GLFW_GAMEPAD_AXIS_LEFT_X)
                 0.0)
        move-y (if gamepad-connected
                 (az/index (az/field gamepad axes)
                           glfw/GLFW_GAMEPAD_AXIS_LEFT_Y)
                 0.0)]
    (set! current
          (InputSnapshot
           {:pointer_x pointer-x
            :pointer_y pointer-y
            :pointer_down pointer-down
            :activate_pressed
            (or pointer-pressed
                (pressed-edge? gamepad-activate previous-gamepad-activate))
            :secondary_down secondary-down
            :pause_pressed (pressed-edge? pause-down previous-pause)
            :restart_pressed (pressed-edge? restart-down previous-restart)
            :rotate_pressed (pressed-edge? rotate-down previous-rotate)
            :inspector_pressed (pressed-edge? inspector-down previous-inspector)
            :build_selection build-selection
            :move_x move-x
            :move_y move-y
            :gamepad_connected gamepad-connected}))
    (set! previous-pause pause-down)
    (set! previous-restart restart-down)
    (set! previous-rotate rotate-down)
    (set! previous-inspector inspector-down)
    (set! previous-gamepad-activate gamepad-activate)
    current))

(az/defn snapshot
  :-
  InputSnapshot
  []
  current)
