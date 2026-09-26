(ns learn.example.integer-literals
  (:require [aguafria.zig :as az]))

(az/defconst decimal-int 98222)
(az/defconst hex-int 0xff)
(az/defconst another-hex-int 0xFF)
(az/defconst octal-int (az/number-literal "0o755"))
(az/defconst binary-int (az/number-literal "0b11110000"))

;; underscores may be placed between two digits as a visual separator
(az/defconst one-billion (az/number-literal "1_000_000_000"))
(az/defconst binary-mask (az/number-literal "0b1_1111_1111"))
(az/defconst permissions (az/number-literal "0o7_5_5"))
(az/defconst big-address (az/number-literal "0xFF80_0000_0000_0000"))
