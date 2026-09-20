(ns learn.example.panic-handler
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.std.process :as process]
            [aguafria.zig :as az]))

(az/defn main :void []
  (ak/setRuntimeSafety true)
  (let [^{:var :u8} value 255]
    (ak/+= value 1)))

(az/defconst panic {:attrs #{:public}} (debug/FullPanic report-panic))

(az/defn- report-panic :noreturn
  [[message [:slice-const :u8]] [first-trace-address [:optional :usize]]]
  (set! _ first-trace-address)
  (debug/print "Panic! {s}\n" [message])
  (process/exit 1))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
