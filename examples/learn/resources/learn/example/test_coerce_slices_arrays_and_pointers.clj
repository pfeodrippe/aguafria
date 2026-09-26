(ns learn.example.test-coerce-slices-arrays-and-pointers
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

;; You can assign constant pointers to arrays to a slice with
;; const modifier on the element type. Useful in particular for
;; String literals.
(az/deftest *const-N-T-to-const-T
  (let [x1 (k/as "hello" [:slice-const :u8])
        x2
        (k/as (k/& (az/init [\h \e \l \l 111] [:array 5 :u8])) [:slice-const :u8])
        y
        (k/as (k/& (az/init [1.2 3.4] [:array 2 :f32])) [:slice-const :f32])]
    (try (testing/expectEqualStrings x1 x2))
    (try (testing/expectEqual 1.2 (az/get y 0)))))

;; Likewise, it works when the destination type is an error union.
(az/deftest *const-N-T-to-E!const-T
  (let [x1 (k/as "hello" [:error-union :anyerror [:slice-const :u8]])
        x2
        (k/as (k/& (az/init [\h \e \l \l 111] [:array 5 :u8])) [:error-union :anyerror [:slice-const :u8]])
        y
        (k/as (k/& (az/init [1.2 3.4] [:array 2 :f32])) [:error-union :anyerror [:slice-const :f32]])]
    (try (testing/expectEqualStrings (try x1) (try x2)))
    (try (testing/expectEqual 1.2 (az/get (try y) 0)))))

;; Likewise, it works when the destination type is an optional.
(az/deftest *const-N-T-to-?const-T
  (let [x1 (k/as "hello" [:optional [:slice-const :u8]])
        x2
        (k/as (k/& (az/init [\h \e \l \l 111] [:array 5 :u8])) [:optional [:slice-const :u8]])
        y
        (k/as (k/& (az/init [1.2 3.4] [:array 2 :f32])) [:optional [:slice-const :f32]])]
    (try (testing/expectEqualStrings (az/unwrap x1) (az/unwrap x2)))
    (try (testing/expectEqual 1.2 (az/get (az/unwrap y) 0)))))

;; In this cast, the array length becomes the slice length.
(az/deftest *N-T-to-T
  (let [buf (k/var (deref "hello") [:array 5 :u8])
        x (k/as (k/& buf) [:slice :u8])
        buf2 (az/init [1.2 3.4] [:array 2 :f32])
        x2 (k/as (k/& buf2) [:slice-const :f32])]
    (try (testing/expectEqualStrings "hello" x))
    (try (testing/expectEqualSlices
          (az/type :f32)
          (k/& (az/init [1.2 3.4] [:array 2 :f32]))
          x2))))

;; Single-item pointers to arrays can be coerced to many-item pointers.
(az/deftest *N-T-to-*T
  (let [buf (k/var (deref "hello") [:array 5 :u8])
        x (k/as (k/& buf) [:many :u8])]
    (try (testing/expectEqual \o (az/get x 4)))
    ;; x[5] would be an uncaught out of bounds pointer dereference!
    ))

;; Likewise, it works when the destination type is an optional.
(az/deftest *N-T-to-?*T
  (let [buf (k/var (deref "hello") [:array 5 :u8])
        x (k/as (k/& buf) [:optional [:many :u8]])]
    (try (testing/expectEqual \o (az/get (az/unwrap x) 4)))))

;; Single-item pointers can be cast to len-1 single-item arrays.
(az/deftest *T-to-*1T
  (let [x (k/var 1234 :i32)
        y (k/as (k/& x) [:* [:array 1 :i32]])
        z (k/as y [:many :i32])]
    (try (testing/expectEqual 1234 (az/get z 0)))))

;; Sentinel-terminated slices can be coerced into sentinel-terminated pointers
(az/deftest xT-to-*xT
  (let [buf (k/as "hello" [:pointer {:sentinel 0, :size :slice, :const? true} :u8])
        buf2 (k/as buf [:sentinel-const :u8 0])]
    (try (testing/expectEqual \o (az/get buf2 4)))))

(comment
  (*const-N-T-to-const-T)
  (*const-N-T-to-E!const-T)
  (*const-N-T-to-?const-T)
  (*N-T-to-T)
  (*N-T-to-*T)
  (*N-T-to-?*T)
  (*T-to-*1T)
  (xT-to-*xT))
