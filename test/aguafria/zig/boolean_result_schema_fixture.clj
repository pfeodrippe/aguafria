(ns aguafria.zig.boolean-result-schema-fixture
  (:refer-clojure :only [let])
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn never-run :void []
  (let [flag (k/var true :bool)]
    (k/= flag false)
    (k/= :_ (k/& flag)))
  (let [flag (k/var false :bool)]
    (k/= :_ (k/& flag)))
  (k/panic "Observation must never execute this body"))
