(ns ghostty-agua.bridge
  "Small JVM-shaped calls into generated Ghostty declarations."
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]
            [ghostty.src.terminal.c.focus :as ghostty-focus]))

(a/defn focus-final-byte :u8
  "Return the final byte of Ghostty's VT focus sequence.

  The generated C API uses caller-provided pointers; this JVM-shaped wrapper
  keeps those native details inside Zig while still calling the converted
  Ghostty function directly."
  [[gained? :bool]]
  (let [bytes (k/var undefined [:array 3 :u8])
        written (k/var 0 :usize)]
    (= :_
       (ghostty-focus/encode
        (if gained? :gained :lost)
        (k/& bytes)
        (:len bytes)
        (k/& written)))
    (a/get bytes (- written 1))))
