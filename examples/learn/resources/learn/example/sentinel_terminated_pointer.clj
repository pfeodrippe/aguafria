(ns learn.example.sentinel-terminated-pointer
  (:require [aguafria.std.c :as c]
            [aguafria.zig :as az]))

(az/defn main [:error-union :anyerror :void] []
  ;; std.c exposes the same C printf declaration used by the reference.
  (set! _ (c/printf "Hello, world!\n"))
  (let [message "Hello, world!\n"
        ^{:zig/type [:array (az/field message :len) :u8]} bytes @message]
    ;; Copying to an ordinary array drops the type's sentinel guarantee.
    ;; Intentionally rejected: printf requires a zero-terminated pointer.
    (set! _ (c/printf (& bytes)))))
