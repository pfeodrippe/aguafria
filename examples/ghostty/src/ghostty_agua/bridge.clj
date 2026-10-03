(ns ghostty-agua.bridge
  "Small JVM-shaped calls into generated Ghostty declarations."
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as a]
            [ghostty.src.terminal.c.focus :as ghostty-focus]))

(a/defn focus-final-byte :u8
  "Return the final byte of Ghostty's VT focus sequence.

  The generated C API uses caller-provided pointers; this JVM-shaped wrapper
  keeps those native details inside Zig while still calling the converted
  Ghostty function directly."
  [[gained? :bool]]
  (ak/var bytes [:array 3 :u8] ak/undefined)
  (ak/var written :usize 0)
  (set! _
        (ghostty-focus/encode
         (if gained? :.gained :.lost)
         (ak/& bytes)
         (a/field bytes len)
         (ak/& written)))
  (ak/return (a/index bytes (- written 1))))
