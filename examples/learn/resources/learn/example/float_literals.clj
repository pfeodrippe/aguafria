(ns learn.example.float-literals
  (:require [aguafria.zig :as a]))

(a/defconst floating-point (a/number-literal "123.0E+77"))
(a/defconst another-float 123.0)
(a/defconst yet-another (a/number-literal "123.0e+77"))

(a/defconst hex-floating-point (a/number-literal "0x103.70p-5"))
(a/defconst another-hex-float (a/number-literal "0x103.70"))
(a/defconst yet-another-hex-float (a/number-literal "0x103.70P-5"))

;; underscores may be placed between two digits as a visual separator
(a/defconst lightspeed (a/number-literal "299_792_458.000_000"))
(a/defconst nanosecond (a/number-literal "0.000_000_001"))
(a/defconst more-hex (a/number-literal "0x1234_5678.9ABC_CDEFp-10"))
