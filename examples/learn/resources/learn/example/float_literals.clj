(ns learn.example.float-literals
  (:require [aguafria.zig :as az]))

;; Preserve the exact Zig literal, without rounding through a JVM double.
(az/defconst floating-point (az/number-literal "123.0E+77"))
(az/defconst another-float 123.0)
(az/defconst yet-another (az/number-literal "123.0e+77"))

(az/defconst hex-floating-point (az/number-literal "0x103.70p-5"))
(az/defconst another-hex-float (az/number-literal "0x103.70"))
(az/defconst yet-another-hex-float (az/number-literal "0x103.70P-5"))

;; Underscores may be placed between two digits as a visual separator.
(az/defconst lightspeed (az/number-literal "299_792_458.000_000"))
(az/defconst nanosecond (az/number-literal "0.000_000_001"))
(az/defconst more-hex (az/number-literal "0x1234_5678.9ABC_CDEFp-10"))

(comment
  ;; This excerpt has no standalone entry point; evaluate its declarations above in their documented context.
  )
