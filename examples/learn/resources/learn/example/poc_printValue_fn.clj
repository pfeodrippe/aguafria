(ns learn.example.poc-printValue-fn
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defstruct Writer
  [(az/fn print-value [:error-union :void]
     [[self [:* Writer]] [value :anytype]]
     (az/switch-stmt (ak/typeInfo (ak/TypeOf value))
       (case [:.int]
         (ak/return ((az/field self :write-int) value)))
       (case [:.float]
         (ak/return ((az/field self :write-float) value)))
       (case [:.pointer]
         (ak/return ((az/field self :write) value)))
       (az/case-else
         (ak/compileError
          (az/op "++" "Unable to print type '" (ak/typeName (ak/TypeOf value)) "'")))))

   (az/fn- write [:error-union :void]
     [[self [:* Writer]] [value [:slice-const :u8]]]
     (ak/= :_ self)
     (ak/= :_ value))

   (az/fn- write-int [:error-union :void]
     [[self [:* Writer]] [value :anytype]]
     (ak/= :_ self)
     (ak/= :_ value))

   (az/fn- write-float [:error-union :void]
     [[self [:* Writer]] [value :anytype]]
     (ak/= :_ self)
     (ak/= :_ value))])
