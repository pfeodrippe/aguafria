(ns learn.examples.idiomatic-advanced.wasi-args
  "Converted from wasi_args.zig"
  (:require aguafria.std
            [aguafria.std.debug :as debug]
            [aguafria.std.process :as process]
            [aguafria.zig :as az]))

;; Target: wasm32-wasi.
(az/defn main :void
  {:zig/qualifiers "!"}
  [[init process/Init]]
  (let [args (try ((az/field (az/field (az/field init :minimal) :args) :toSlice)
                   ((az/field (az/field init :arena) :allocator))))]
    (for [[index (az/op ".." 0)] [argument args]]
      (debug/print "{d}: {s}\n" [index argument]))))
