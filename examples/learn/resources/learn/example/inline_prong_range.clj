(ns learn.example.inline-prong-range
  (:require [aguafria.keyword :as k]
            [aguafria.std.lang.Type :as type-info]
            [aguafria.std.lang.Type.Struct :as struct-info]
            [aguafria.zig :as az]))

(az/defn- isFieldOptional [:error-union :bool]
  [[T {:attrs #{k/comptime}} :type] [field-index :usize]]
  (let [field-types (-> (k/typeInfo T) type-info/-struct struct-info/-field_types)]
    (switch field-index
            (az/inline-case [(k/... 0 (k/- (:len field-types) 1))] [idx]
                            (k/== (k/typeInfo (az/get field-types idx)) :.optional))
            (az/case-else (k/return (az/error-value :IndexOutOfBounds))))))
