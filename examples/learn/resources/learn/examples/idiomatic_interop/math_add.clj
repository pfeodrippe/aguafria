(ns learn.examples.idiomatic-interop.math-add
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.std.math :as math]
            [aguafria.zig :as az]))

(az/defn main :void
  {:zig/qualifiers "!"}
  []
  (let [^{:var :u8} byte 255]
    (set! byte
      (az/if-capture {:payload [result] :error [error]} (math/add :u8 byte 1)
        result
        (az/block
          (debug/print "unable to add one: {s}\n" [(ak/errorName error)])
          (ak/return error))))
    (debug/print "result: {}\n" [byte])))
