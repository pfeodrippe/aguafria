(ns learn.example.test-pointer-casting
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst native-endian ((:endian (:arch (:cpu builtin/target)))))

(az/deftest pointer-casting
  (let [bytes (az/array [0x10 0x20 0x30 0x40]
                        {:align (k/alignOf :u32)}
                        :u8)
        u32-ptr (k/as (k/ptrCast (k/& bytes)) [:*const :u32])]
    ;; Because we directly reinterpreted bytes of memory, the `u32` value we
    ;; load from `u32_ptr` depends on the target endian:
    (k/switch native-endian
              (case [:.little] (try (testing/expectEqual 0x40302010 @u32-ptr)))
              (case [:.big] (try (testing/expectEqual 0x10203040 @u32-ptr))))

    ;; To instead reinterpret the logical bit representation of `bytes` with no
    ;; dependency on the target endian, use `@bitCast`, which always places
    ;; earlier array elements into less-significant bits:
    (try (testing/expectEqual 0x40302010 (-> bytes k/bitCast (k/as :u32))))))

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
