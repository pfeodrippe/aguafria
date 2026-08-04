(ns simple-game.desktop
  "GLFW host loop for the hot-reloadable Flecs/Vulkan game."
  (:refer-clojure :exclude [run!])
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as std-debug]
            [aguafria.zig :as az]
            [simple-game.desktop-bindings]
            [simple-game.bindings.glfw :as glfw]
            [simple-game.game :as game]
            [simple-game.host :as host]
            [simple-game.vulkan :as renderer]))

(az/defstruct DesktopSnapshot
  "Inspectable native desktop-loop state."
  {:layout :extern}
  [[:running :bool]
   [:pointer_down :bool]
   [:frames :u64]
   [:counter :u32]
   [:shader_index :u32]])

(az/defvar running false)

(az/defvar window [:optional [:* glfw/GLFWwindow]] null)

(az/defn request-stop!
  "Ask the main-thread GLFW loop to finish after its current frame."
  :- :void
  []
  (set! running false))

(az/defn enable-sticky-input!
  "Latch short mouse presses until the 120 Hz loop observes them."
  :-
  :void
  []
  (when (ak/!= window null)
    (host/reset-input! (az/unwrap window))))

(az/defn run!
  "Run GLFW, Flecs, and Vulkan on the JVM main thread while nREPL stays live."
  :- :bool
  []
  (glfw/glfwInitVulkanLoader glfw/vkGetInstanceProcAddr)
  (std-debug/assert (ak/== (glfw/glfwInit) glfw/GLFW_TRUE))
  (glfw/glfwWindowHint glfw/GLFW_CLIENT_API glfw/GLFW_NO_API)
  (glfw/glfwWindowHint glfw/GLFW_RESIZABLE glfw/GLFW_FALSE)
  (set! window (glfw/glfwCreateWindow 720 540 "Aguafria · Flecs · Vulkan" null null))
  (std-debug/assert (ak/!= window null))
  (enable-sticky-input!)
  (std-debug/assert (renderer/initialize-renderer! window))
  (set! _ (game/initialize!))
  (set! running true)
  (ak/while (and running (ak/== (glfw/glfwWindowShouldClose window) glfw/GLFW_FALSE))
    (set! _ (renderer/render! (host/frame! (az/unwrap window)))))
  (renderer/shutdown-renderer!)
  (glfw/glfwDestroyWindow window)
  (set! window null)
  (glfw/glfwTerminate)
  (set! running false)
  true)

(az/defn desktop-snapshot
  "Inspect the running desktop host without stopping its native loop."
  :- DesktopSnapshot
  []
  (let [render-state (renderer/renderer-snapshot)
        game-state (game/snapshot)]
    (DesktopSnapshot
     {:running running
      :pointer_down host/previous-pointer-down
      :frames (az/field render-state frames)
      :counter (az/field game-state counter)
      :shader_index (az/field game-state shader_index)})))
