(ns aguafria.zig.discovery-inspection-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defstruct Holder
  [[:value :u32]
   (az/fn create Holder []
     (Holder {:value 7}))])

(az/defn- stop-now :noreturn []
  (k/unreachable))

(az/defn panic-now :noreturn []
  (k/panic "Inspection must not execute native code"))

(az/defn inspect-assignment :void [[input :usize]]
  (let [result (k/var 0 :u8)]
    (k/= result (k/intCast input))
    (k/= :_ (k/& result))
    (stop-now)))
