(ns learn.example.test-pointer-casting
  (:refer-clojure :only [])
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defconst native-endian ((:endian (:arch (:cpu builtin/target)))))

(a/deftest pointer-casting
  (let [bytes (a/array [0x10 0x20 0x30 0x40] {:align (alignOf :u32)} :u8)
        u32-ptr (-> (k/& bytes)
                    (ptrCast)
                    (as [:*const :u32]))]
    ;; Because we directly reinterpreted bytes of memory, the `u32` value we
    ;; load from `u32_ptr` depends on the target endian:
    (switch native-endian
      :little (k/try (testing/expectEqual 0x40302010 @u32-ptr))
      :big (k/try (testing/expectEqual 0x10203040 @u32-ptr)))

    ;; To instead reinterpret the logical bit representation of `bytes` with no
    ;; dependency on the target endian, use `@bitCast`, which always places
    ;; earlier array elements into less-significant bits:
    (k/try (testing/expectEqual 0x40302010 (-> bytes bitCast (as :u32))))))

(a/deftest pointer-child-type
  ;; pointer types have a `child` field which tells you the type they point to.
  (k/try (testing/expectEqual
          (a/type :u32)
          (-> (typeInfo [:* :u32])
              :pointer
              :child))))

(comment
  (pointer-casting)
  (pointer-child-type))
