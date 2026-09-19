(ns learn.examples.idiomatic-basics.testing-null-with-if
  (:require aguafria.std
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [^{:zig/type [:optional :i32]} optional-number nil]
    (az/if-capture-stmt {:payload [number]} optional-number
      (debug/print "got number: {}\n" [number])
      (debug/print "it's null\n" []))))
