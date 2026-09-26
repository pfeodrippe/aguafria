(ns learn.example.test-inferred-error-sets
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(ns-unmap *ns* 'Error)

(az/defconst Error (az/type [:error-set [:Overflow]]))

;; With an inferred error set
(az/defn add-inferred [:error-union T]
  [[T {:attrs #{k/comptime}} :type] [a T] [b T]]
  (let [ov (k/addWithOverflow a b)]
    (when (k/!= (az/get ov 1) 0)
      (k/return (az/error-value :Overflow)))
    (az/get ov 0)))

;; With an explicit error set
(az/defn add-explicit [:error-union Error T]
  [[T {:attrs #{k/comptime}} :type] [a T] [b T]]
  (let [ov (k/addWithOverflow a b)]
    (when (k/!= (az/get ov 1) 0)
      (k/return (az/error-value :Overflow)))
    (az/get ov 0)))

(az/deftest inferred-error-set
  (az/if-capture-stmt {:payload [_] :error [err]} (add-inferred :u8 255 1)
                      (k/unreachable)
                      (az/switch-stmt err
                        ;; ok
                                      (case [(az/error-value :Overflow)] (az/block)))))

(comment
  (inferred-error-set))
