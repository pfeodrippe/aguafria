(ns learn.example.packed-struct-mmio
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defstruct GpioRegister
  {:layout :packed, :argument :u8}
  [[:GPIO0 :bool]
   [:GPIO1 :bool]
   [:GPIO2 :bool]
   [:GPIO3 :bool]
   [:reserved {:default 0} :u4]])

(az/defconst gpio [:pointer {:size :one :volatile? true} GpioRegister]
  (ak/ptrFromInt 0x0123))

;; Write the entire packed register, not an individual bit field.
(az/defn write-to-gpio :void [[new-states GpioRegister]]
  (ak/= @gpio new-states))
