(ns aguafria.zig.fixed-length-prepared-fixture
  (:refer-clojure :only [let])
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn lengths :!void []
  (let [array (k/var (a/array [1 2 3 4] :i32))
        start (k/var 0 :usize)
        dynamic-slice (a/slice array start (:len array))
        fixed (a/slice array 0 (:len array))
        reslice (a/slice array 0 (:len dynamic-slice))
        copied-length (k/var (:len array) :usize)
        copy-slice (a/slice array 0 copied-length)]
    (k/= :_ (k/& start))
    (k/= :_ (k/& copied-length))
    (k/= :_ (k/== (k/TypeOf (:len array)) :usize))
    (k/= :_ (k/== (:len array) 4))
    (k/try (testing/expectEqual 4 (:len array)))
    (k/= :_ (k/== (k/TypeOf fixed)
                  (a/type [:* [:array (:len array) :i32]])))
    (k/= :_ (k/== (k/TypeOf dynamic-slice) (a/type [:slice :i32])))
    (k/= :_ (k/== (k/TypeOf reslice) (a/type [:slice :i32])))
    (k/= :_ (k/== (k/TypeOf copy-slice) (a/type [:slice :i32])))
    (k/= :_ (a/get fixed 2))
    (k/= :_ (:len (k/& array))))
  (k/panic "Preparation must not execute lengths"))
