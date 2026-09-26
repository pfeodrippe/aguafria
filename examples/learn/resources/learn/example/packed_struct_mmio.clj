(ns learn.example.packed-struct-mmio
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defstruct GpioRegister
  {:layout :packed, :argument :u8}
  [[:GPIO0 :bool]
   [:GPIO1 :bool]
   [:GPIO2 :bool]
   [:GPIO3 :bool]
   [:reserved {:default 0} :u4]])

(az/defconst gpio [:pointer {:size :one :volatile? true} GpioRegister]
  (k/ptrFromInt 0x0123))

(az/defn write-to-gpio :void [[new-states GpioRegister]]
  ;; Example of what not to do:
  ;; BAD! gpio.GPIO0 = true; BAD!

  ;; Instead, do this:
  (k/= @gpio new-states))
