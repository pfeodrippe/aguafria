(ns learn.example.TopLevelFields
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deffield foo :u32)
(az/deffield bar :u64)

(az/defconst TopLevelFields (k/This))

(az/defn init TopLevelFields [[value :u32]]
  (az/object [[:foo value]
              [:bar (k/* value 10)]]))

(comment
  (init 42))
