(ns learn.example.inline-prong-range
  (:require [aguafria.keyword :as k]
            [aguafria.std.builtin.Type :as type-info]
            [aguafria.std.builtin.Type.Struct :as struct-info]
            [aguafria.std.builtin.Type.StructField :as field-info]
            [aguafria.zig :as az]))

(az/defn- isFieldOptional [:error-union :bool]
  [[T {:attrs #{k/comptime}} :type] [field-index :usize]]
  (let [fields (-> (k/typeInfo T) type-info/-struct struct-info/-fields)]
    (switch field-index
      (az/inline-case [(az/op "..." 0 (k/- (az/field fields :len) 1))] [index]
        (k/== (k/typeInfo (field-info/-type (az/index fields index))) :.optional))
      (az/case-else (k/return (az/error-value :IndexOutOfBounds))))))
