(ns learn.example.inline-prong-range
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- field-optional? [:error-union :bool]
  [[T {:zig/prefix "comptime"} :type] [field-index :usize]]
  (let [fields (az/field (az/field (ak/typeInfo T) :struct) :fields)]
    (switch field-index
      (az/inline-case [(az/op "..." 0 (- (az/field fields :len) 1))] [index]
        (== (ak/typeInfo (az/field (az/index fields index) :type)) :.optional))
      (az/case-else (ak/return (az/error-value :IndexOutOfBounds))))))

(comment
  ;; This excerpt has no standalone entry point; evaluate its declarations above in their documented context.
  )
