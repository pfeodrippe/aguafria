(ns learn.example.test-coerce-slices-arrays-and-pointers
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

;; You can assign constant pointers to arrays to a slice with
;; const modifier on the element type. Useful in particular for
;; String literals.
(a/deftest *const-N-T-to-const-T
  (let [x1 (k/as "hello" [:slice-const :u8])
        x2
        (k/as (k/& (a/init [\h \e \l \l 111] [:array 5 :u8])) [:slice-const :u8])
        y
        (k/as (k/& (a/init [1.2 3.4] [:array 2 :f32])) [:slice-const :f32])]
    (try (testing/expectEqualStrings x1 x2))
    (try (testing/expectEqual 1.2 (a/get y 0)))))

;; Likewise, it works when the destination type is an error union.
(a/deftest *const-N-T-to-E!const-T
  (let [x1 (k/as "hello" [:error-union :anyerror [:slice-const :u8]])
        x2
        (k/as (k/& (a/init [\h \e \l \l 111] [:array 5 :u8])) [:error-union :anyerror [:slice-const :u8]])
        y
        (k/as (k/& (a/init [1.2 3.4] [:array 2 :f32])) [:error-union :anyerror [:slice-const :f32]])]
    (k/try (testing/expectEqualStrings (k/try x1) (k/try x2)))
    (k/try (testing/expectEqual 1.2 (a/get (k/try y) 0)))))

;; Likewise, it works when the destination type is an optional.
(a/deftest *const-N-T-to-?const-T
  (let [x1 (k/as "hello" [:optional [:slice-const :u8]])
        x2
        (k/as (k/& (a/init [\h \e \l \l 111] [:array 5 :u8])) [:optional [:slice-const :u8]])
        y
        (k/as (k/& (a/init [1.2 3.4] [:array 2 :f32])) [:optional [:slice-const :f32]])]
    (try (testing/expectEqualStrings (a/unwrap x1) (a/unwrap x2)))
    (try (testing/expectEqual 1.2 (a/get (a/unwrap y) 0)))))

;; In this cast, the array length becomes the slice length.
(a/deftest *N-T-to-T
  (let [buf (k/var (a/deref "hello") [:array 5 :u8])
        x (k/as (k/& buf) [:slice :u8])
        buf2 (a/init [1.2 3.4] [:array 2 :f32])
        x2 (k/as (k/& buf2) [:slice-const :f32])]
    (try (testing/expectEqualStrings "hello" x))
    (try (testing/expectEqualSlices
          :f32
          (k/& (a/init [1.2 3.4] [:array 2 :f32]))
          x2))))

;; Single-item pointers to arrays can be coerced to many-item pointers.
(a/deftest *N-T-to-*T
  (let [buf (k/var (a/deref "hello") [:array 5 :u8])
        x (k/as (k/& buf) [:many :u8])]
    (try (testing/expectEqual \o (a/get x 4)))
    ;; x[5] would be an uncaught out of bounds pointer dereference!
    ))

;; Likewise, it works when the destination type is an optional.
(a/deftest *N-T-to-?*T
  (let [buf (k/var (a/deref "hello") [:array 5 :u8])
        x (k/as (k/& buf) [:optional [:many :u8]])]
    (try (testing/expectEqual \o (a/get (a/unwrap x) 4)))))

;; Single-item pointers can be cast to len-1 single-item arrays.
(a/deftest *T-to-*1T
  (let [x (k/var 1234 :i32)
        y (k/as (k/& x) [:* [:array 1 :i32]])
        z (k/as y [:many :i32])]
    (try (testing/expectEqual 1234 (a/get z 0)))))

;; Sentinel-terminated slices can be coerced into sentinel-terminated pointers
(a/deftest xT-to-*xT
  (let [buf (k/as "hello" [:* {:sentinel 0, :size :slice, :const? true} :u8])
        buf2 (k/as buf [:sentinel-const :u8 0])]
    (try (testing/expectEqual \o (a/get buf2 4)))))

(comment
  (*const-N-T-to-const-T)
  (*const-N-T-to-E!const-T)
  (*const-N-T-to-?const-T)
  (*N-T-to-T)
  (*N-T-to-*T)
  (*N-T-to-?*T)
  (*T-to-*1T)
  (xT-to-*xT))
