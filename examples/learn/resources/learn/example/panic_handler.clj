(ns learn.example.panic-handler
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.std.process :as process]
            [aguafria.zig :as a]))

(a/defn main :void []
  (setRuntimeSafety true)
  (let [x (k/var 255 :u8)]
    ;; Let's overflow this integer!
    (+= x 1)))

(a/defn- myPanic :noreturn
  [[msg [:slice-const :u8]] [first-trace-addr [:optional :usize]]]
  (= :_ first-trace-addr)
  (debug/print "Panic! {s}\n" [msg])
  (process/exit 1))

(a/defconst panic {:attrs #{pub}} (debug/FullPanic myPanic))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
