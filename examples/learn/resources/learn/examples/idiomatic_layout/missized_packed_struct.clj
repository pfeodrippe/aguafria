(ns learn.examples.idiomatic-layout.missized-packed-struct
  (:require [aguafria.zig :as az]))

(az/deftest missized-packed-struct-test
  ;; Intentionally rejected: the fields total 24 bits, but the backing integer has 32.
  (let [WrongSize (az/container {:kind :struct :layout :packed :argument :u32}
                    (az/field-decl :a :u16)
                    (az/field-decl :b :u8))]
    (set! _ (az/init WrongSize {:a 4 :b 2}))))
