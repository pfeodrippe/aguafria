(ns aguafria.zig.discovery-function-pointer-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defconst Callback
  (a/type [:*const [:fn {} [{:name :value :type :i32}] :i32]]))

(a/defconst CCallback
  (a/type [:*const [:fn {:callconv :.c} [{:name :value :type :i32}] :i32]]))

(a/defextern abs :i32 {:callconv :.c} [[value :i32]])

(a/defn- increment :i32 [[value :i32]]
  (k/+ value 1))

(a/defn- decrement :i32 [[value :i32]]
  (k/- value 1))

(a/defn apply-callback :i32 [[callback Callback] [value :i32]]
  (callback value))

(a/defn apply-c-callback :i32 [[callback CCallback] [value :i32]]
  (callback value))

(a/deftest observe-callbacks
  (k/= :_ (k/& increment))
  (k/= :_ (k/& decrement))
  (k/= :_ (apply-callback increment 4))
  (k/= :_ (apply-callback decrement 4))
  (k/= :_ (apply-c-callback abs -4))
  (k/unreachable))
