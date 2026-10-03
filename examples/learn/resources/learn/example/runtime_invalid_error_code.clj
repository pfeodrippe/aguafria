(ns learn.example.runtime-invalid-error-code
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn main :void []
  (let [err (a/error-value :AnError)
        number (k/var (k/+ (k/intFromError err) 500))]
    (k/= :_ (k/& number))
    (let [invalid-err (k/errorFromInt number)]
      (debug/print "value: {}\n" [invalid-err]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
