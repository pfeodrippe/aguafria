(ns aguafria.zig.discovery-inspection-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defstruct Holder
  [[:value :u32]
   (a/fn create Holder []
     (Holder {:value 7}))])

(a/defn- stop-now :noreturn []
  (k/unreachable))

(a/defn panic-now :noreturn []
  (k/panic "Inspection must not execute native code"))

(a/defn inspect-assignment :void [[input :usize]]
  (let [result (k/var 0 :u8)]
    (k/= result (k/intCast input))
    (k/= :_ (k/& result))
    (stop-now)))
