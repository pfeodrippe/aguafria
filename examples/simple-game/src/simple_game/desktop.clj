(ns simple-game.desktop
  "GLFW host loop for the hot-reloadable Flecs/Vulkan coco factory."
  (:refer-clojure :exclude [run!])
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as std-debug]
            [aguafria.zig :as az]
            [simple-game.audio :as audio]
            [simple-game.desktop-bindings]
            [simple-game.bindings.glfw :as glfw]
            [simple-game.factory :as factory]
            [simple-game.game :as game]
            [simple-game.host :as host]
            [simple-game.vulkan :as renderer]))

(az/defstruct DesktopSnapshot
  "Inspectable native desktop-loop state."
  {:layout :extern}
  [[:running :bool]
   [:pointer_down :bool]
   [:frames :u64]
   [:buildings :u32]
   [:houses_completed :u32]])

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
  (set! _ (audio/initialize!))
  (glfw/glfwWindowHint glfw/GLFW_CLIENT_API glfw/GLFW_NO_API)
  (glfw/glfwWindowHint glfw/GLFW_RESIZABLE glfw/GLFW_FALSE)
  (set! window (glfw/glfwCreateWindow 720 540 "Coco House Works · Aguafria" null null))
  (std-debug/assert (ak/!= window null))
  (enable-sticky-input!)
  (std-debug/assert (renderer/initialize-renderer! window))
  (set! _ (game/initialize!))
  (set! running true)
  (ak/while (and running (ak/== (glfw/glfwWindowShouldClose window) glfw/GLFW_FALSE))
    (set! _ (renderer/render! (host/frame! (az/unwrap window)))))
  (renderer/shutdown-renderer!)
  (game/shutdown)
  (audio/shutdown!)
  (glfw/glfwDestroyWindow window)
  (set! window null)
  (glfw/glfwTerminate)
  (set! running false)
  true)

(az/defn run-for-frames!
  "Run the release renderer for a bounded warmup and measured frame count."
  :-
  host/FrameTiming
  [[warmup-frames :u32]
   [measured-frames :u32]]
  (glfw/glfwInitVulkanLoader glfw/vkGetInstanceProcAddr)
  (std-debug/assert (ak/== (glfw/glfwInit) glfw/GLFW_TRUE))
  (set! _ (audio/initialize!))
  (glfw/glfwWindowHint glfw/GLFW_CLIENT_API glfw/GLFW_NO_API)
  (glfw/glfwWindowHint glfw/GLFW_RESIZABLE glfw/GLFW_FALSE)
  (set! window
        (glfw/glfwCreateWindow 720 540
                               "Coco House Works · ReleaseFast Probe"
                               null null))
  (std-debug/assert (ak/!= window null))
  (enable-sticky-input!)
  (std-debug/assert (renderer/initialize-renderer! window))
  (set! _ (game/initialize!))
  (set! running true)
  (dotimes [_ warmup-frames]
    (set! _ (renderer/render! (host/frame! (az/unwrap window)))))
  (host/reset-frame-timing!)
  (dotimes [_ measured-frames]
    (set! _ (renderer/render! (host/frame! (az/unwrap window)))))
  (let [timing (host/frame-timing)]
    (renderer/shutdown-renderer!)
    (game/shutdown)
    (audio/shutdown!)
    (glfw/glfwDestroyWindow window)
    (set! window null)
    (glfw/glfwTerminate)
    (set! running false)
    timing))

(az/defn desktop-snapshot
  "Inspect the running desktop host without stopping its native loop."
  :- DesktopSnapshot
  []
  (let [render-state (renderer/renderer-snapshot)
        factory-state (factory/snapshot)]
    (DesktopSnapshot
     {:running running
      :pointer_down host/previous-pointer-down
      :frames (az/field render-state frames)
      :buildings (az/field factory-state buildings)
      :houses_completed (az/field factory-state houses_completed)})))
