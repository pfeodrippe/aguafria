(ns learn.example.sentinel-terminated-pointer
  (:require [aguafria.keyword :as ak]
            [aguafria.std.c :as c]
            [aguafria.zig :as az]))

(az/defn main [:error-union :anyerror :void] []
  ;; std.c exposes the same C printf declaration used by the reference.
  (ak/= :_ (c/printf "Hello, world!\n"))
  (let [message "Hello, world!\n"
        bytes (ak/as @message [:array (az/field message :len) :u8])]
    ;; Copying to an ordinary array drops the type's sentinel guarantee.
    ;; Intentionally rejected: printf requires a zero-terminated pointer.
    (ak/= :_ (c/printf (& bytes)))))

(comment
  (main))
