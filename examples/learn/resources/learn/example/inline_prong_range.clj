(ns learn.example.inline-prong-range
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.lang.Type :as type-info]
            [aguafria.std.lang.Type.Struct :as struct-info]
            [aguafria.zig :as a]))

(a/defn- isFieldOptional [:error-union :bool]
  [[T {:attrs #{comptime}} :type] [field-index :usize]]
  (let [field-types (-> (typeInfo T) type-info/-struct struct-info/-field_types)]
    (switch field-index
      (k/... 0 (- (:len field-types) 1))
      (inline (a/with-block :_ [idx] (== (typeInfo (a/get field-types idx)) :optional)))
      (return (a/error-value :IndexOutOfBounds)))))
