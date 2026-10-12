(ns aguafria.zig.source-location-fixture
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defconst native-endian ((:endian (:arch (:cpu builtin/target)))))

(a/defn pointer-casting [:error-union :anyerror :void] []
  (let [bytes (a/array [0x10 0x20 0x30 0x40] {:align (k/alignOf :u32)} :u8)
        pointer (k/as (k/ptrCast (k/& bytes)) [:*const :u32])]
    (k/switch native-endian
      :little (try (testing/expectEqual 0x40302010 @pointer))
      :big (try (testing/expectEqual 0x10203040 @pointer)))
    (try (testing/expectEqual 0x40302010 (-> bytes k/bitCast (k/as :u32))))))
