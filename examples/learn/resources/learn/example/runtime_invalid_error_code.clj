(ns learn.example.runtime-invalid-error-code
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [error (az/error-value :AnError)
        error-code (k/var (k/+ (k/intFromError error) 500))]
    (k/= :_ (k/& error-code))
    (let [invalid-error (k/errorFromInt error-code)]
      (debug/print "value: {}\n" [invalid-error]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
