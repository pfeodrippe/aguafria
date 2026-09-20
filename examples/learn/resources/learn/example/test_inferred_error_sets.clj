(ns learn.example.test-inferred-error-sets
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(ns-unmap *ns* 'Error)

(az/defconst Error (az/type [:error-set [:Overflow]]))

(az/defn add-inferred [:error-union T]
  [[T {:zig/prefix "comptime"} :type] [left T] [right T]]
  (let [sum (ak/addWithOverflow left right)]
    (when (ak/!= (az/index sum 1) 0)
      (ak/return (az/error-value :Overflow)))
    (az/index sum 0)))

(az/defn add-explicit [:error-union Error T]
  [[T {:zig/prefix "comptime"} :type] [left T] [right T]]
  (let [sum (ak/addWithOverflow left right)]
    (when (ak/!= (az/index sum 1) 0)
      (ak/return (az/error-value :Overflow)))
    (az/index sum 0)))

(az/deftest inferred-error-set-test
  (az/if-capture-stmt {:payload [_] :error [error]} (add-inferred :u8 255 1)
                      (ak/unreachable)
                      (az/switch-stmt error
                        (case [(az/error-value :Overflow)] (az/block)))))

(comment
  (inferred-error-set-test))
