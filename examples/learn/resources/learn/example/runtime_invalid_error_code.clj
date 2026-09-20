(ns learn.example.runtime-invalid-error-code
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [error (az/error-value :AnError)
        error-code (ak/var (+ (ak/intFromError error) 500))]
    (ak/= :_ (ak/& error-code))
    (let [invalid-error (ak/errorFromInt error-code)]
      (debug/print "value: {}\n" [invalid-error]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
