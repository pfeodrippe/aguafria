(ns learn.example.test-incorrect-pointer-alignment
  (:require [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn read-misaligned-word :u32 [[bytes [:slice :u8]]]
  (let [four-bytes (az/slice bytes 1 5)
        words (mem/bytesAsSlice
                (az/type :u32)
                (ak/as (az/type [:pointer {:size :slice :align 4} :u8])
                       (ak/alignCast four-bytes)))]
    (az/index words 0)))

(az/deftest pointer-alignment-safety-test
  (let [^{:var true :zig/align 4} words
        (az/array-init [:array _ :u32] [0x11111111 0x11111111])
        bytes (mem/sliceAsBytes (az/slice words 0))]
    ;; Intentionally panics: offsetting aligned storage by one byte breaks
    ;; the four-byte alignment promised by read-misaligned-word's cast.
    (try (testing/expectEqual 0x11111111 (read-misaligned-word bytes)))))
