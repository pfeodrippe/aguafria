(ns learn.example.sentinel-terminated-pointer
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.c :as libc]
            [aguafria.zig :as a]))

;; This is also available as `std.c.printf`.
(a/defn main [:error-union :anyerror :void] []
  (= :_ (libc/printf "Hello, world!\n")) ; OK
  (let [msg "Hello, world!\n"
        non-null-terminated-msg (as @msg [:array (:len msg) :u8])]
    (= :_ (libc/printf (k/& non-null-terminated-msg)))))

(comment
  (main))
