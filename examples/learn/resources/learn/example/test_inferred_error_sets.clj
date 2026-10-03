(ns learn.example.test-inferred-error-sets
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(ns-unmap *ns* 'Error)

(a/defconst Error (a/type [:error-set [:Overflow]]))

;; With an inferred error set
(a/defn add-inferred [:error-union T]
  [[T {:attrs #{k/comptime}} :type] [a T] [b T]]
  (let [ov (k/addWithOverflow a b)]
    (when (k/!= (a/get ov 1) 0)
      (k/return (a/error-value :Overflow)))
    (a/get ov 0)))

;; With an explicit error set
(a/defn add-explicit [:error-union Error T]
  [[T {:attrs #{k/comptime}} :type] [a T] [b T]]
  (let [ov (k/addWithOverflow a b)]
    (when (k/!= (a/get ov 1) 0)
      (k/return (a/error-value :Overflow)))
    (a/get ov 0)))

(a/deftest inferred-error-set
  (a/if-capture-stmt {:payload [_] :error [err]} (add-inferred :u8 255 1)
                     (k/unreachable)
                     (a/switch-stmt err
                        ;; ok
                                    (case [(a/error-value :Overflow)] (a/block)))))

(comment
  (inferred-error-set))
