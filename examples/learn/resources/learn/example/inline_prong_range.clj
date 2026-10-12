(ns learn.example.inline-prong-range
  (:require [aguafria.keyword :as k]
            [aguafria.std.lang.Type :as type-info]
            [aguafria.std.lang.Type.Struct :as struct-info]
            [aguafria.zig :as a]))

(a/defn- isFieldOptional [:error-union :bool]
  [[T {:attrs #{k/comptime}} :type] [field-index :usize]]
  (let [field-types (-> (k/typeInfo T) type-info/-struct struct-info/-field_types)]
    (k/switch field-index
      (k/... 0 (k/- (:len field-types) 1))
      (k/inline (a/with-block :_ [idx] (k/== (k/typeInfo (a/get field-types idx)) :optional)))
      (k/return (a/error-value :IndexOutOfBounds)))))
