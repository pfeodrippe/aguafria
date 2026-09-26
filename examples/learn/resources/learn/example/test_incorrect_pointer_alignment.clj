(ns learn.example.test-incorrect-pointer-alignment
  (:require [aguafria.keyword :as k]
            [aguafria.std.mem :as mem]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn foo :u32 [[bytes [:slice :u8]]]
  (let [four-bytes (az/slice bytes 1 5)
        words (mem/bytesAsSlice
               (az/type :u32)
               (k/as (k/alignCast four-bytes)
                      (az/type [:pointer {:size :slice :align 4} :u8])))]
    (az/get words 0)))

(az/deftest pointer-alignment-safety-test
  (let [words
        (k/var (az/array [0x11111111 0x11111111] :u32) nil {:zig/align 4})
        bytes (mem/sliceAsBytes (az/slice words 0))]
    ;; Intentionally panics: offsetting aligned storage by one byte breaks
    ;; the four-byte alignment promised by read-misaligned-word's cast.
    (try (testing/expectEqual 0x11111111 (foo bytes)))))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (pointer-alignment-safety-test))
