(ns learn.example.test-pointer-casting
  (:require [aguafria.keyword :as k]
            [aguafria.std.mem :as mem]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest pointer-casting
  (let [bytes (az/array [0x12 0x12 0x12 0x12]
                        {:align (k/alignOf :u32)}
                        :u8)
        u32-ptr (k/as (k/ptrCast (k/& bytes)) [:*const :u32])]
    (try (testing/expectEqual 0x12121212 @u32-ptr))
    ;; Even this example is contrived - there are better ways to do the above than
    ;; pointer casting. For example, using a slice narrowing cast:
    (let [u32-value (az/get (mem/bytesAsSlice :u32 (az/slice bytes 0)) 0)]
      (try (testing/expectEqual 0x12121212 u32-value)))
    ;; And even another way, the most straightforward way to do it:
    (try (testing/expectEqual 0x12121212 (-> bytes k/bitCast (k/as :u32))))))

(az/deftest pointer-child-type
  ;; pointer types have a `child` field which tells you the type they point to.
  (try (testing/expectEqual
        (az/type :u32)
        (-> (k/typeInfo [:* :u32])
            :pointer
            :child))))

(comment
  (pointer-casting)
  (pointer-child-type))
