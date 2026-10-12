(ns aguafria.zig.native-root-aot-fixture
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.process :as process]
            [aguafria.zig :as a]))

(a/defn library-mode :bool []
  (k/== builtin/output_mode :Lib))

(a/defn main :void []
  (k/panic "root panic"))

(a/defn- project-panic :noreturn
  [[message [:slice-const :u8]] [address [:optional :usize]]]
  (k/= :_ address)
  (debug/print "AUTHORED ROOT: {s}\n" [message])
  (process/exit 19))

(a/defconst panic {:attrs #{k/pub}} (debug/FullPanic project-panic))
