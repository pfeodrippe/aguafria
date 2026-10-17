(ns learn.example.test-peer-type-resolution
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- boolToStr [:slice-const :u8]
  [[b :bool]]
  (if b
    "true"
    "false"))

(a/defn- testPeerResolveArrayConstSlice :!void
  [[b :bool]]
  (let [value1 (if b
                 "aoeu"
                 (as "zz" [:slice-const :u8]))
        value2 (if b
                 (as "zz" [:slice-const :u8])
                 "aoeu")]
    (k/try (testing/expectEqualStrings "aoeu" value1))
    (k/try (testing/expectEqualStrings "zz" value2))))

(a/defn- peerTypeTAndOptionalT [:optional :usize]
  [[c :bool] [b :bool]]
  (when c
    (return
     (if b
       nil
       (as 0 :usize))))
  (as 3 :usize))

(a/defn- peerTypeEmptyArrayAndSlice [:slice-const :u8]
  [[a :bool] [slice [:slice-const :u8]]]
  (when a
    (return (k/& (a/array [] :u8))))
  (a/slice slice 0 1))

(a/defn- peerTypeEmptyArrayAndSliceAndError [:error-union :anyerror [:slice :u8]]
  [[a :bool] [slice [:slice :u8]]]
  (when a
    (return (k/& (a/array [] :u8))))
  (a/slice slice 0 1))

(a/deftest peer-resolve-int-widening
  (let [a (i8 12)
        b (i16 34)
        c (+ a b)]
    (k/try (testing/expectEqual 46 c))
    (k/try (testing/expectEqual :i16 (TypeOf c)))))

(a/deftest peer-resolve-small-int-and-float
  ;; This only works for integer types that can coerce to the float type.
  ;; Larger integer types will cause a compiler error; no float widening occurs.
  (let [i (k/var 12 :u8)
        f (k/var 34 :f32)]
    (= :_ [(k/& i) (k/& f)])
    (let [x (+ i f)]
      (k/try (testing/expectEqual x 46.0))
      (k/try (testing/expectEqual (TypeOf x) :f32)))))

(a/deftest peer-resolve-arrays-of-different-size-to-const-slice
  (k/try (testing/expectEqualStrings "true" (boolToStr true)))
  (k/try (testing/expectEqualStrings "false" (boolToStr false)))
  (k/try (comptime (testing/expectEqualStrings "true" (boolToStr true))))
  (k/try (comptime (testing/expectEqualStrings "false" (boolToStr false)))))

(a/deftest peer-resolve-array-and-const-slice
  (k/try (testPeerResolveArrayConstSlice true))
  (k/try (comptime (testPeerResolveArrayConstSlice true))))

(a/deftest peer-type-resolution-?T-and-T
  (k/try (testing/expectEqual 0 (a/unwrap (peerTypeTAndOptionalT true false))))
  (k/try (testing/expectEqual 3 (a/unwrap (peerTypeTAndOptionalT false false))))
  (comptime
   (a/block
    (k/try (testing/expectEqual 0 (a/unwrap (peerTypeTAndOptionalT true false))))
    (k/try (testing/expectEqual 3 (a/unwrap (peerTypeTAndOptionalT false false)))))))

(a/deftest peer-type-resolution-*zero-u8-and-const-u8-slice
  (k/try (testing/expectEqual 0 (:len (peerTypeEmptyArrayAndSlice true "hi"))))
  (k/try (testing/expectEqual 1 (:len (peerTypeEmptyArrayAndSlice false "hi"))))
  (comptime
   (a/block
    (k/try (testing/expectEqual 0 (:len (peerTypeEmptyArrayAndSlice true "hi"))))
    (k/try (testing/expectEqual 1 (:len (peerTypeEmptyArrayAndSlice false "hi")))))))

(a/deftest peer-type-resolution-*zero-u8-const-u8-slice-and-anyerror-u8-slice
  (let [data (k/var (a/deref "hi"))
        slice (a/slice data 0)]
    (k/try (testing/expectEqual 0 (:len (k/try (peerTypeEmptyArrayAndSliceAndError true slice)))))
    (k/try (testing/expectEqual 1 (:len (k/try (peerTypeEmptyArrayAndSliceAndError false slice))))))
  (comptime
   (let [data (k/var (a/deref "hi"))
         slice (a/slice data 0)]
     (k/try (testing/expectEqual 0 (:len (k/try (peerTypeEmptyArrayAndSliceAndError true slice)))))
     (k/try (testing/expectEqual 1 (:len (k/try (peerTypeEmptyArrayAndSliceAndError false slice))))))))

(a/deftest peer-type-resolution-*const-T-and-?*T
  (let [a (as (ptrFromInt 0x123456780) [:*const :usize])
        b (as (ptrFromInt 0x123456780) [:optional [:* :usize]])]
    (k/try (testing/expectEqual a b))
    (k/try (testing/expectEqual b a))))

(a/deftest peer-type-resolution-error-union-switch
  ;; The non-error and error cases are only peers if the error case is just a switch expression;
  ;; the pattern `if (x) {...} else |err| blk: { switch (err) {...} }` does not consider the
  ;; non-error and error case to be peers.
  (let [a (k/var 0 [:error-union [:error-set [:A :B :C]] :u32])]
    (= :_ (k/& a))
    (let [b (a/if-capture {:payload [x] :error [err]} a
                          (+ x 3)
                          (switch err
                            (a/error-value :A) 0
                            (a/error-value :B) 1
                            (a/error-value :C) nil))]
      (k/try (testing/expectEqual (a/type [:optional :u32]) (TypeOf b))))

    ;; The non-error and error cases are only peers if the error case is just a switch expression;
    ;; the pattern `x catch |err| blk: { switch (err) {...} }` does not consider the unwrapped `x`
    ;; and error case to be peers.
    (let [c (a/catch-capture [err] a
                             (switch err
                               (a/error-value :A) 0
                               (a/error-value :B) 1
                               (a/error-value :C) nil))]
      (k/try (testing/expectEqual (a/type [:optional :u32]) (TypeOf c))))))

(comment
  (peer-resolve-int-widening)
  (peer-resolve-small-int-and-float)
  (peer-resolve-arrays-of-different-size-to-const-slice)
  (peer-resolve-array-and-const-slice)
  (peer-type-resolution-?T-and-T)
  (peer-type-resolution-*zero-u8-and-const-u8-slice)
  (peer-type-resolution-*zero-u8-const-u8-slice-and-anyerror-u8-slice)
  (peer-type-resolution-*const-T-and-?*T)
  (peer-type-resolution-error-union-switch))
