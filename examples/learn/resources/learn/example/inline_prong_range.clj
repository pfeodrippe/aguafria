(ns learn.example.inline-prong-range
  (:require [aguafria.keyword :as k]
            [aguafria.std.lang.Type :as type-info]
            [aguafria.std.lang.Type.Struct :as struct-info]
            [aguafria.zig :as a]))

(a/defn- isFieldOptional [:error-union :bool]
  [[T {:attrs #{k/comptime}} :type] [field-index :usize]]
  (let [field-types (-> (k/typeInfo T) type-info/-struct struct-info/-field_types)]
    (switch field-index
            (a/inline-case [(k/... 0 (k/- (:len field-types) 1))] [idx]
                           (k/== (k/typeInfo (a/get field-types idx)) :.optional))
            (a/case-else (k/return (a/error-value :IndexOutOfBounds))))))
