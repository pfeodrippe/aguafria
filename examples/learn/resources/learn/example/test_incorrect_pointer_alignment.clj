(ns learn.example.test-incorrect-pointer-alignment
  (:require [aguafria.keyword :as k]
            [aguafria.std.mem :as mem]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn foo :u32 [[bytes [:slice :u8]]]
  (let [slice4 (az/slice bytes 1 5)
        int-slice (mem/bytesAsSlice
                   (az/type :u32)
                   (k/as (k/alignCast slice4)
                         (az/type [:pointer {:size :slice :align 4} :u8])))]
    (az/get int-slice 0)))

(az/deftest pointer-alignment-safety
  (let [array
        (k/var (az/array [0x11111111 0x11111111] :u32) nil {:zig/align 4})
        bytes (mem/sliceAsBytes (az/slice array 0))]
    (try (testing/expectEqual 0x11111111 (foo bytes)))))

(comment
  (pointer-alignment-safety))
