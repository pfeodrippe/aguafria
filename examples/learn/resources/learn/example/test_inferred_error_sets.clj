(ns learn.example.test-inferred-error-sets
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]
            [clojure.core :as c]))

(c/ns-unmap c/*ns* 'Error)

(a/defconst Error (a/type [:error-set [:Overflow]]))

;; With an inferred error set
(a/defn add-inferred [:error-union T]
  [[T {:attrs #{comptime}} :type] [a T] [b T]]
  (let [ov (addWithOverflow a b)]
    (when (!= (a/get ov 1) 0)
      (return (a/error-value :Overflow)))
    (a/get ov 0)))

;; With an explicit error set
(a/defn add-explicit [:error-union Error T]
  [[T {:attrs #{comptime}} :type] [a T] [b T]]
  (let [ov (addWithOverflow a b)]
    (when (!= (a/get ov 1) 0)
      (return (a/error-value :Overflow)))
    (a/get ov 0)))

(a/deftest inferred-error-set
  (a/if-capture-stmt {:payload [_] :error [err]} (add-inferred :u8 255 1)
                     (unreachable)
                     (switch err
                       ;; ok
                       (a/error-value :Overflow) (a/block))))

(comment
  (inferred-error-set))
