(ns simple-game.web
  "Thin WebAssembly target for the shared GLFW/Flecs/Aguafria game."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as std-debug]
            [aguafria.zig :as az]
            [simple-game.web-bindings]
            [simple-game.bindings.emscripten :as emscripten]
            [simple-game.bindings.glfw :as glfw]
            [simple-game.bindings.webgl :as gl]
            [simple-game.game :as game]
            [simple-game.host :as host]
            [simple-game.scene :as scene]))

(az/defvar window [:optional [:* glfw/GLFWwindow]] null)

(az/defvar frame-height :i32 0)

(az/defn backend-clear-rect
  "WebGL implementation of the shared scene's rectangle operation."
  {:attrs #{:public}}
  :-
  :void
  [[color scene/Color]
   [x :i32]
   [y :i32]
   [width :i32]
   [height :i32]]
  (when (and (> width 0) (> height 0))
    (gl/glScissor x (- frame-height y height) width height)
    (gl/glClearColor (az/field color r)
                     (az/field color g)
                     (az/field color b)
                     (az/field color a))
    (gl/glClear gl/GL_COLOR_BUFFER_BIT)))

(az/defn render!
  "Execute the common scene through WebGL and present its GLFW framebuffer."
  {:attrs #{:public}}
  :-
  :void
  [[packet game/RenderPacket]]
  (let [^{:var true :zig/type :i32} frame-width 0]
    (glfw/glfwGetFramebufferSize (az/unwrap window)
                                 (ak/& frame-width)
                                 (ak/& frame-height))
    (gl/glDisable gl/GL_SCISSOR_TEST)
    (gl/glViewport 0 0 frame-width frame-height)
    (gl/glClearColor 0.025 0.032 0.055 1.0)
    (gl/glClear gl/GL_COLOR_BUFFER_BIT)
    (gl/glEnable gl/GL_SCISSOR_TEST)
    (scene/draw-frame (ak/& backend-clear-rect)
                      packet frame-width frame-height)
    (gl/glFlush)
    (glfw/glfwSwapBuffers (az/unwrap window))))

(az/defn web-frame
  "Emscripten callback: the same shared GLFW input and Flecs frame as desktop."
  {:attrs #{:public :export}}
  :-
  :void
  []
  (render! (host/frame! (az/unwrap window))))

(az/defn web-start
  "Create the GLFW ES context and enter Emscripten's native main loop."
  {:attrs #{:public :export}}
  :-
  :void
  []
  (std-debug/assert (ak/== (glfw/glfwInit) glfw/GLFW_TRUE))
  (glfw/glfwWindowHint glfw/GLFW_CLIENT_API glfw/GLFW_OPENGL_ES_API)
  (glfw/glfwWindowHint glfw/GLFW_CONTEXT_VERSION_MAJOR 3)
  (glfw/glfwWindowHint glfw/GLFW_CONTEXT_VERSION_MINOR 0)
  (glfw/glfwWindowHint glfw/GLFW_RESIZABLE glfw/GLFW_FALSE)
  (set! window (glfw/glfwCreateWindow 720 540 "Aguafria · Flecs" null null))
  (std-debug/assert (ak/!= window null))
  (glfw/glfwMakeContextCurrent (az/unwrap window))
  (host/reset-input! (az/unwrap window))
  (set! _ (game/initialize!))
  (gl/glEnable gl/GL_SCISSOR_TEST)
  (emscripten/emscripten_set_main_loop (ak/& web-frame) 0 false))
