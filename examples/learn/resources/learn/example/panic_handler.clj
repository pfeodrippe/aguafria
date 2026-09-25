(ns learn.example.panic-handler
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.process :as process]
            [aguafria.zig :as az]))

(az/defn main :void []
  (k/setRuntimeSafety true)
  (let [value (k/var 255 :u8)]
    (k/+= value 1)))

(az/defn- myPanic :noreturn
  [[message [:slice-const :u8]] [first-trace-address [:optional :usize]]]
  (k/= :_ first-trace-address)
  (debug/print "Panic! {s}\n" [message])
  (process/exit 1))

(az/defconst panic {:attrs #{k/pub}} (debug/FullPanic myPanic))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
