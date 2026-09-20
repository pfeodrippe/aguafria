(ns aguafria.std
  "Bootstrap for EDN-derived Clojure Vars representing Zig `@import(\"std\")`.

  Nested `aguafria.std.*` namespaces can be required directly. This root reads
  the prepared std catalog, materializes all namespaces and Vars in memory,
  and registers them with Clojure's loader."
  (:require [aguafria.zig.std :as std]))

(std/install-all!)
