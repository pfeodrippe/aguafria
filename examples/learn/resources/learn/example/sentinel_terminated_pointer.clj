(ns learn.example.sentinel-terminated-pointer
  (:require [aguafria.keyword :as k]
            [aguafria.std.c :as c]
            [aguafria.zig :as az]))

;; This is also available as `std.c.printf`.
(az/defn main [:error-union :anyerror :void] []
  (k/= :_ (c/printf "Hello, world!\n")) ; OK
  (let [msg "Hello, world!\n"
        non-null-terminated-msg (k/as @msg [:array (:len msg) :u8])]
    (k/= :_ (c/printf (k/& non-null-terminated-msg)))))

(comment
  (main))
