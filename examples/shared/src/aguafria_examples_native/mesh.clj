(ns aguafria-examples-native.mesh
  "Shared mapped Vulkan vertex ABI; examples own their frame construction."
  (:require [aguafria.zig :as az]))

(az/defstruct GpuVertex
  "Position and RGB color consumed by the shared Vulkan triangle pipeline."
  {:layout :extern}
  [[:x :f32]
   [:y :f32]
   [:z :f32]
   [:r :f32]
   [:g :f32]
   [:b :f32]])

(az/defconst frame-capacity :usize 131072)

