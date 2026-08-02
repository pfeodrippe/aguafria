(ns aguafria.std
  "Bootstrap for EDN-derived Clojure Vars representing Zig `@import(\"std\")`.

  Require this namespace before nested `aguafria.std.*` namespaces. It reads
  the checked-in std catalog, materializes all namespaces and Vars in memory,
  and registers them with Clojure's loader."
  (:require [aguafria.zig.std :as std]))

(std/install-all!)
