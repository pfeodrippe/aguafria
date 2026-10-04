(ns aguafria.zig.precompile-host-fixture)

(defn host-only []
  (throw (ex-info "Preparation must not run this Clojure function" {})))
