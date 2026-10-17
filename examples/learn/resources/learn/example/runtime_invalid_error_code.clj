(ns learn.example.runtime-invalid-error-code
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn main :void []
  (let [err (a/error-value :AnError)
        number (k/var (+ (intFromError err) 500))]
    (= :_ (k/& number))
    (let [invalid-err (errorFromInt number)]
      (debug/print "value: {}\n" [invalid-err]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
