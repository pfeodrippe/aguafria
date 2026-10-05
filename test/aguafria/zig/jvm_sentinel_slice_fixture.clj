(ns aguafria.zig.jvm-sentinel-slice-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest runtime-sentinel-slice
  (let [array (k/var (a/array [3 2 1 0 3 2 1 0] :u8))
        length (k/var 3 :usize)]
    (k/= :_ (k/& length))
    (let [slice (a/slice-sentinel array 0 length 0)]
      (k/try (testing/expectEqual (a/type [:* {:size :slice :sentinel 0} :u8])
                                  (k/TypeOf slice)))
      (k/try (testing/expectEqual 3 (:len slice)))
      (k/+= (a/get slice 1) 7)
      (k/try (testing/expectEqual 9 (a/get array 1))))))

(a/deftest constant-sentinel-slice
  (let [array (a/array [1 2 3 0] :u8)
        slice (a/slice-sentinel array 0 3 0)]
    (k/try (testing/expectEqual (a/type [:*const [:array 3 {:sentinel 0} :u8]])
                                (k/TypeOf slice)))
    (k/try (testing/expectEqual 3 (:len slice)))
    (k/try (testing/expectEqual 0 (a/get slice 3)))))
