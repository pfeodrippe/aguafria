(ns learn.example.destructuring-vectors
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

;; Interleave the first two lanes from each vector (like punpckldq).
(az/defn unpack [:vector 4 :f32]
  [[left [:vector 4 :f32]] [right [:vector 4 :f32]]]
  (let [[a c _ _] left
        [b d _ _] right]
    [a b c d]))

(az/defn main :void
  []
  (let [^{:zig/type [:vector 4 :f32]} left [1.0 2.0 3.0 4.0]
        ^{:zig/type [:vector 4 :f32]} right [5.0 6.0 7.0 8.0]]
    (debug/print "{}" [(unpack left right)])))
