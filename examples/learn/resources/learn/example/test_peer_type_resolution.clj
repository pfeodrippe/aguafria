(ns learn.example.test-peer-type-resolution
  (:require [aguafria.keyword :as k]
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
                 (k/as "zz" [:slice-const :u8]))
        value2 (if b
                 (k/as "zz" [:slice-const :u8])
                 "aoeu")]
    (try (testing/expectEqualStrings "aoeu" value1))
    (try (testing/expectEqualStrings "zz" value2))))

(a/defn- peerTypeTAndOptionalT [:optional :usize]
  [[c :bool] [b :bool]]
  (when c
    (k/return
     (if b
       nil
       (k/as 0 :usize))))
  (k/as 3 :usize))

(a/defn- peerTypeEmptyArrayAndSlice [:slice-const :u8]
  [[a :bool] [slice [:slice-const :u8]]]
  (when a
    (k/return (k/& (a/array [] :u8))))
  (a/slice slice 0 1))

(a/defn- peerTypeEmptyArrayAndSliceAndError [:error-union :anyerror [:slice :u8]]
  [[a :bool] [slice [:slice :u8]]]
  (when a
    (k/return (k/& (a/array [] :u8))))
  (a/slice slice 0 1))

(a/deftest peer-resolve-int-widening
  (let [a (k/i8 12)
        b (k/i16 34)
        c (k/+ a b)]
    (try (testing/expectEqual 46 c))
    (try (testing/expectEqual :i16 (k/TypeOf c)))))

(a/deftest peer-resolve-small-int-and-float
  ;; This only works for integer types that can coerce to the float type.
  ;; Larger integer types will cause a compiler error; no float widening occurs.
  (let [i (k/var 12 :u8)
        f (k/var 34 :f32)]
    (k/= :_ [(k/& i) (k/& f)])
    (let [x (k/+ i f)]
      (try (testing/expectEqual x 46.0))
      (try (testing/expectEqual (k/TypeOf x) :f32)))))

(a/deftest peer-resolve-arrays-of-different-size-to-const-slice
  (try (testing/expectEqualStrings "true" (boolToStr true)))
  (try (testing/expectEqualStrings "false" (boolToStr false)))
  (try (k/comptime (testing/expectEqualStrings "true" (boolToStr true))))
  (try (k/comptime (testing/expectEqualStrings "false" (boolToStr false)))))

(a/deftest peer-resolve-array-and-const-slice
  (try (testPeerResolveArrayConstSlice true))
  (try (k/comptime (testPeerResolveArrayConstSlice true))))

(a/deftest peer-type-resolution-?T-and-T
  (try (testing/expectEqual 0 (a/unwrap (peerTypeTAndOptionalT true false))))
  (try (testing/expectEqual 3 (a/unwrap (peerTypeTAndOptionalT false false))))
  (k/comptime
   (a/block
    (try (testing/expectEqual 0 (a/unwrap (peerTypeTAndOptionalT true false))))
    (try (testing/expectEqual 3 (a/unwrap (peerTypeTAndOptionalT false false)))))))

(a/deftest peer-type-resolution-*zero-u8-and-const-u8-slice
  (try (testing/expectEqual 0 (:len (peerTypeEmptyArrayAndSlice true "hi"))))
  (try (testing/expectEqual 1 (:len (peerTypeEmptyArrayAndSlice false "hi"))))
  (k/comptime
   (a/block
    (try (testing/expectEqual 0 (:len (peerTypeEmptyArrayAndSlice true "hi"))))
    (try (testing/expectEqual 1 (:len (peerTypeEmptyArrayAndSlice false "hi")))))))

(a/deftest peer-type-resolution-*zero-u8-const-u8-slice-and-anyerror-u8-slice
  (let [data (k/var (a/deref "hi"))
        slice (a/slice data 0)]
    (try (testing/expectEqual 0 (:len (k/try (peerTypeEmptyArrayAndSliceAndError true slice)))))
    (try (testing/expectEqual 1 (:len (k/try (peerTypeEmptyArrayAndSliceAndError false slice))))))
  (k/comptime
   (let [data (k/var (a/deref "hi"))
         slice (a/slice data 0)]
     (try (testing/expectEqual 0 (:len (k/try (peerTypeEmptyArrayAndSliceAndError true slice)))))
     (try (testing/expectEqual 1 (:len (k/try (peerTypeEmptyArrayAndSliceAndError false slice))))))))

(a/deftest peer-type-resolution-*const-T-and-?*T
  (let [a (k/as (k/ptrFromInt 0x123456780) [:*const :usize])
        b (k/as (k/ptrFromInt 0x123456780) [:optional [:* :usize]])]
    (try (testing/expectEqual a b))
    (try (testing/expectEqual b a))))

(a/deftest peer-type-resolution-error-union-switch
  ;; The non-error and error cases are only peers if the error case is just a switch expression;
  ;; the pattern `if (x) {...} else |err| blk: { switch (err) {...} }` does not consider the
  ;; non-error and error case to be peers.
  (let [a (k/var 0 [:error-union [:error-set [:A :B :C]] :u32])]
    (k/= :_ (k/& a))
    (let [b (a/if-capture {:payload [x] :error [err]} a
                          (k/+ x 3)
                          (k/switch err
                                    (case [(a/error-value :A)] 0)
                                    (case [(a/error-value :B)] 1)
                                    (case [(a/error-value :C)] nil)))]
      (try (testing/expectEqual (a/type [:optional :u32]) (k/TypeOf b))))

    ;; The non-error and error cases are only peers if the error case is just a switch expression;
    ;; the pattern `x catch |err| blk: { switch (err) {...} }` does not consider the unwrapped `x`
    ;; and error case to be peers.
    (let [c (a/catch-capture [err] a
                             (k/switch err
                                       (case [(a/error-value :A)] 0)
                                       (case [(a/error-value :B)] 1)
                                       (case [(a/error-value :C)] nil)))]
      (try (testing/expectEqual (a/type [:optional :u32]) (k/TypeOf c))))))

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
