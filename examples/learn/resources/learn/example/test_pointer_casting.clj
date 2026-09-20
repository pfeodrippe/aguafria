(ns learn.example.test-pointer-casting
  (:require [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest pointer-casting-test
  (let [^{:zig/align (ak/alignOf (az/type :u32))}
        bytes (az/array-init [:array _ :u8] [0x12 0x12 0x12 0x12])
        ^{:zig/type [:*const :u32]} pointer (ak/ptrCast (& bytes))]
    (try (testing/expectEqual 0x12121212 @pointer))
    ;; A slice conversion exposes its element count as well as its pointer.
    (let [words (mem/bytesAsSlice (az/type :u32) (az/slice bytes 0))]
      (try (testing/expectEqual 0x12121212 (az/index words 0))))
    ;; A bit cast copies the bits into a value instead of creating an alias.
    (try (testing/expectEqual 0x12121212 (ak/as :u32 (ak/bitCast bytes))))))

(az/deftest pointer-child-type-test
  (try (testing/expectEqual
        (az/type :u32)
        (-> (ak/typeInfo (az/type [:* :u32]))
            (az/field :pointer)
            (az/field :child)))))

(comment
  (pointer-casting-test)
  (pointer-child-type-test))
