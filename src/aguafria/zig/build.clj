(ns aguafria.zig.build
  "Operational helpers for producing standalone artifacts from Aguafria code."
  (:require [aguafria.zig.runtime :as runtime]))

(defn load-source-only!
  "Load an Aguafria root namespace without compiling development dylibs.

  Declarations and ordinary Clojure Vars are still registered and inspectable.
  A later `az/build!` emits the complete dependency graph and invokes Zig once
  for the requested standalone artifact."
  [namespace]
  (when-not (symbol? namespace)
    (throw (ex-info "Aguafria build namespace must be a symbol"
                    {:namespace namespace})))
  (binding [runtime/*source-only-registration?* true]
    (require namespace))
  (runtime/module-info namespace))
