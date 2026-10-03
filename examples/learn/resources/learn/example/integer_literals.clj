(ns learn.example.integer-literals
  (:require [aguafria.zig :as a]))

(a/defconst decimal-int 98222)
(a/defconst hex-int 0xff)
(a/defconst another-hex-int 0xFF)
(a/defconst octal-int (a/number-literal "0o755"))
(a/defconst binary-int (a/number-literal "0b11110000"))

;; underscores may be placed between two digits as a visual separator
(a/defconst one-billion (a/number-literal "1_000_000_000"))
(a/defconst binary-mask (a/number-literal "0b1_1111_1111"))
(a/defconst permissions (a/number-literal "0o7_5_5"))
(a/defconst big-address (a/number-literal "0xFF80_0000_0000_0000"))
