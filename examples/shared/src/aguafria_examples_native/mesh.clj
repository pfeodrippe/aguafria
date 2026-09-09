(ns aguafria-examples-native.mesh
  "Shared mapped Vulkan vertex ABI; examples own their frame construction."
  (:require [aguafria.zig :as az]))

(az/defstruct GpuVertex
  "64-byte vertex: projected position, albedo, world normal/position,
  roughness and orthographic view direction. Negative roughness is unlit UI."
  {:layout :extern}
  [[:x :f32]
   [:y :f32]
   [:z :f32]
   [:r :f32]
   [:g :f32]
   [:b :f32]
   [:nx :f32] [:ny :f32] [:nz :f32]
   [:wx :f32] [:wy :f32] [:wz :f32]
   [:roughness :f32]
   [:vx :f32] [:vy :f32] [:vz :f32]])

(az/defconst frame-capacity :usize 524288)

(az/defstruct GpuInstance
  "One independently posed mesh part. Metre-space quaternion/translation and
  authored pivot; shadow_z is kilometres. mode=1 draws its projected shadow."
  {:layout :extern}
  [[:qx :f32] [:qy :f32] [:qz :f32] [:qw :f32]
   [:x :f32] [:y :f32] [:z :f32] [:scale :f32]
   [:origin_x :f32] [:origin_y :f32] [:origin_z :f32] [:shadow_z :f32]
   [:r :f32] [:g :f32] [:b :f32] [:mode :f32]])

(az/defstruct InstanceCamera
  "48-byte push constants shared by all instances of a draw."
  {:layout :extern}
  [[:x :f32] [:y :f32] [:z :f32] [:zoom :f32]
   [:cos_yaw :f32] [:sin_yaw :f32] [:cos_pitch :f32] [:sin_pitch :f32]
   [:fit_x :f32] [:fit_y :f32] [:reserved0 :f32] [:reserved1 :f32]])

(az/defconst instance-capacity :usize 1024)
