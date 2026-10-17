(ns aguafria.zig.fixed-length-prepared-driver
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(defn evaluate! []
  ;; Supplemental ordinary bridge demand, not a call of the enclosing function.
  (let [array (k/var (a/array [1 2 3 4] :i32))
        start (k/var 0 :usize)
        dynamic-slice (a/slice array start (:len array))
        fixed (a/slice array 0 (:len array))
        reslice (a/slice array 0 (:len dynamic-slice))
        copied-length (k/var (:len array) :usize)
        copy-slice (a/slice array 0 copied-length)]
    (k/= :_ (k/& start))
    (k/= :_ (k/& copied-length))
    (assert (a/value (k/== (:len array) 4)))
    (k/try (testing/expectEqual 4 (:len array)))
    {:length (a/value (:len array))
     :length-type (a/value (k/== (k/TypeOf (:len array)) :usize))
     :fixed (a/value (k/== (k/TypeOf fixed)
                           (a/type [:* [:array (:len array) :i32]])))
     :runtime (a/value (k/== (k/TypeOf dynamic-slice) (a/type [:slice :i32])))
     :slice-end-runtime (a/value (k/== (k/TypeOf reslice) (a/type [:slice :i32])))
     :copied-end-runtime (a/value (k/== (k/TypeOf copy-slice) (a/type [:slice :i32])))
     :element (a/value (a/get fixed 2))
     :pointer-length (a/value (:len (k/& array)))}))
