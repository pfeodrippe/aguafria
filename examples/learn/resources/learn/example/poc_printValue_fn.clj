(ns learn.example.poc-printValue-fn
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defstruct Writer
  [(a/fn print-value [:error-union :void]
     [[self [:* Writer]] [value :anytype]]
     (switch (typeInfo (TypeOf value))
       :int
       (return ((:write-int self) value))
       :float
       (return ((:write-float self) value))
       :pointer
       (return ((:write self) value))

       (compileError
        (++ "Unable to print type '" (typeName (TypeOf value)) "'"))))

   (a/fn- write [:error-union :void]
          [[self [:* Writer]] [value [:slice-const :u8]]]
          (= :_ self)
          (= :_ value))

   (a/fn- write-int [:error-union :void]
          [[self [:* Writer]] [value :anytype]]
          (= :_ self)
          (= :_ value))

   (a/fn- write-float [:error-union :void]
          [[self [:* Writer]] [value :anytype]]
          (= :_ self)
          (= :_ value))])
