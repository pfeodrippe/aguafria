(ns learn.example.poc-printValue-fn
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defstruct Writer
  [(a/fn print-value [:error-union :void]
     [[self [:* Writer]] [value :anytype]]
     (k/switch (k/typeInfo (k/TypeOf value))
       :int
       (k/return ((:write-int self) value))
       :float
       (k/return ((:write-float self) value))
       :pointer
       (k/return ((:write self) value))

       (k/compileError
        (k/++ "Unable to print type '" (k/typeName (k/TypeOf value)) "'"))))

   (a/fn- write [:error-union :void]
          [[self [:* Writer]] [value [:slice-const :u8]]]
          (k/= :_ self)
          (k/= :_ value))

   (a/fn- write-int [:error-union :void]
          [[self [:* Writer]] [value :anytype]]
          (k/= :_ self)
          (k/= :_ value))

   (a/fn- write-float [:error-union :void]
          [[self [:* Writer]] [value :anytype]]
          (k/= :_ self)
          (k/= :_ value))])
