(ns aguafria.zig.discovery-inspection-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defstruct Holder
  [[:value :u32]
   (a/fn create Holder []
     (Holder {:value 7}))])

(a/defn- stop-now :noreturn []
  (unreachable))

(a/defn panic-now :noreturn []
  (panic "Inspection must not execute native code"))

(a/defn inspect-assignment :void [[input :usize]]
  (let [result (k/var 0 :u8)]
    (= result (intCast input))
    (= :_ (k/& result))
    (stop-now)))
