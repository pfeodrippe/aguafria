(ns learn.examples.idiomatic-interop.entry-point
  (:require aguafria.std
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  "std.start imports the root module and uses this declaration as its entry point.
  It can return void, E!void, u8, or E!u8 for any error set E.
  Returning void exits with code 0; returning u8 uses that value as the status.
  Returning an error prints an Error Return Trace and exits with code 1."
  []
  (debug/print "Hello, World!\n" []))

;; Uncommenting this would suppress std.start's usual logic and ignore main.
;; (az/defconst _start {})
