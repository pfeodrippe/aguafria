(ns learn.example.test-inferred-error-sets
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(ns-unmap *ns* 'Error)

(az/defconst Error (az/type [:error-set [:Overflow]]))

(az/defn add-inferred [:error-union T]
  [[T {:attrs #{k/comptime}} :type] [left T] [right T]]
  (let [sum (k/addWithOverflow left right)]
    (when (k/!= (az/index sum 1) 0)
      (k/return (az/error-value :Overflow)))
    (az/index sum 0)))

(az/defn add-explicit [:error-union Error T]
  [[T {:attrs #{k/comptime}} :type] [left T] [right T]]
  (let [sum (k/addWithOverflow left right)]
    (when (k/!= (az/index sum 1) 0)
      (k/return (az/error-value :Overflow)))
    (az/index sum 0)))

(az/deftest inferred-error-set-test
  (az/if-capture-stmt {:payload [_] :error [error]} (add-inferred :u8 255 1)
                      (k/unreachable)
                      (az/switch-stmt error
                        (case [(az/error-value :Overflow)] (az/block)))))

(comment
  (inferred-error-set-test))
