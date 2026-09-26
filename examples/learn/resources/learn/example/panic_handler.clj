(ns learn.example.panic-handler
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.process :as process]
            [aguafria.zig :as az]))

(az/defn main :void []
  (k/setRuntimeSafety true)
  (let [x (k/var 255 :u8)]
    ;; Let's overflow this integer!
    (k/+= x 1)))

(az/defn- myPanic :noreturn
  [[msg [:slice-const :u8]] [first-trace-addr [:optional :usize]]]
  (k/= :_ first-trace-addr)
  (debug/print "Panic! {s}\n" [msg])
  (process/exit 1))

(az/defconst panic {:attrs #{k/pub}} (debug/FullPanic myPanic))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
