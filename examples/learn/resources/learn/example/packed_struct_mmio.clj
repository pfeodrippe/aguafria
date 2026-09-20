(ns learn.example.packed-struct-mmio
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst GpioRegister
  (az/container {:kind :struct :layout :packed :argument :u8}
    (az/field-decl :GPIO0 :bool)
    (az/field-decl :GPIO1 :bool)
    (az/field-decl :GPIO2 :bool)
    (az/field-decl :GPIO3 :bool)
    (az/field-decl :reserved :u4 0)))

(az/defconst gpio [:pointer {:size :one :volatile? true} GpioRegister]
  (ak/ptrFromInt 0x0123))

;; Write the entire packed register, not an individual bit field.
(az/defn write-to-gpio :void [[new-states GpioRegister]]
  (set! @gpio new-states))

(comment
  ;; Hardware-only MMIO: do not call write-to-gpio in a desktop JVM.
  )
