(ns learn.example.poc-printValue-fn
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defstruct Writer
  [(az/fn print-value [:error-union :void]
     [[self [:* Writer]] [value :anytype]]
     (az/switch-stmt (k/typeInfo (k/TypeOf value))
                     (case [:.int]
                       (k/return ((:write-int self) value)))
                     (case [:.float]
                       (k/return ((:write-float self) value)))
                     (case [:.pointer]
                       (k/return ((:write self) value)))
                     (az/case-else
                      (k/compileError
                       (k/++ "Unable to print type '" (k/typeName (k/TypeOf value)) "'")))))

   (az/fn- write [:error-union :void]
           [[self [:* Writer]] [value [:slice-const :u8]]]
           (k/= :_ self)
           (k/= :_ value))

   (az/fn- write-int [:error-union :void]
           [[self [:* Writer]] [value :anytype]]
           (k/= :_ self)
           (k/= :_ value))

   (az/fn- write-float [:error-union :void]
           [[self [:* Writer]] [value :anytype]]
           (k/= :_ self)
           (k/= :_ value))])
