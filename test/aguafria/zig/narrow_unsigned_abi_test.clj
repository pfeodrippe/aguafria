(ns aguafria.zig.narrow-unsigned-abi-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [clojure.test :refer [deftest is testing]]))

(a/defn pack-bytes :u16 [[lo :u8] [hi :u8]]
  (k/| (k/as lo :u16) (k/<< (k/as hi :u16) 8)))

(a/defn pack-shorts :u32 [[lo :u16] [hi :u16]]
  (k/| (k/as lo :u32) (k/<< (k/as hi :u32) 16)))

(a/defn pack-c-shorts :u32 [[lo :c_ushort] [hi :c_ushort]]
  (k/| (k/as lo :u32) (k/<< (k/as hi :u32) 16)))

(a/defn signed-control :i16 [[lo :i8] [hi :i8]]
  (k/+ (k/as lo :i16) (k/as hi :i16)))

(deftest unsigned-narrow-arguments-do-not-contaminate-their-neighbours
  (testing "high bits in either unsigned argument survive the JVM/native boundary"
    (doseq [[lo hi] [[0 0] [127 128] [128 127] [205 1] [1 205] [205 205] [255 0] [0 255] [255 255]]]
      (is (= (+ lo (bit-shift-left hi 8)) (a/value (pack-bytes lo hi))) (str [lo hi])))
    (doseq [f [pack-shorts pack-c-shorts]
            [lo hi] [[0 0] [32767 32768] [32768 32767] [43981 1] [1 43981] [65535 0] [0 65535] [65535 65535]]]
      (is (= (+ lo (bit-shift-left hi 16)) (a/value (f lo hi))) (str [lo hi]))))
  (testing "native owning arguments use their real storage, including mutations"
    (let [lo (k/var 205 :u8)
          hi (k/u8 1)]
      (is (= 461 (a/value (pack-bytes lo hi))))
      (k/= lo 128)
      (is (= 384 (a/value (pack-bytes lo hi))))))
  (testing "signed byte carriers retain their signed ABI"
    (is (= -129 (a/value (signed-control -128 -1))))
    (is (= -1 (a/value (signed-control -128 127))))))
