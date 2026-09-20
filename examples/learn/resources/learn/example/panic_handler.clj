(ns learn.example.panic-handler
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.std.process :as process]
            [aguafria.zig :as az]))

(az/defn main :void []
  (ak/setRuntimeSafety true)
  (let [value (ak/var 255 :u8)]
    (ak/+= value 1)))

(az/defconst panic {:attrs #{:public}} (debug/FullPanic myPanic))

(az/defn- myPanic :noreturn
  [[message [:slice-const :u8]] [first-trace-address [:optional :usize]]]
  (ak/= :_ first-trace-address)
  (debug/print "Panic! {s}\n" [message])
  (process/exit 1))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
