(ns learn.example.poc-printValue-fn
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst Writer
  (az/container {:kind :struct}
    (az/fn-decl print-value {:attrs #{:public}} :- [:error-union :void]
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
            (ak/++ "Unable to print type '" (ak/typeName (ak/TypeOf value)) "'")))))

    (az/fn-decl write :- [:error-union :void]
      [[self [:* Writer]] [value [:slice-const :u8]]]
      (set! _ self)
      (set! _ value))

    (az/fn-decl write-int :- [:error-union :void]
      [[self [:* Writer]] [value :anytype]]
      (set! _ self)
      (set! _ value))

    (az/fn-decl write-float :- [:error-union :void]
      [[self [:* Writer]] [value :anytype]]
      (set! _ self)
      (set! _ value))))
