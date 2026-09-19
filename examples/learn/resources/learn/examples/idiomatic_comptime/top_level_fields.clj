(ns learn.examples.idiomatic-comptime.top-level-fields
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deffield foo :u32)
(az/deffield bar :u64)

(az/defconst TopLevelFields (ak/This))

(az/defn init TopLevelFields [[value :u32]]
  (az/object [[:foo value]
              [:bar (* value 10)]]))
