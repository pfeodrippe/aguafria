(ns learn.example.TopLevelFields
  "Because this file contains fields, it is a type which is intended to be instantiated, and so
is named in TitleCase instead of snake_case by convention."
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deffield foo :u32)
(az/deffield bar :u64)

(az/defconst TopLevelFields
  "`@This()` can be used to refer to this struct type. In files with fields, it is quite common to
name the type here, so it can be easily referenced by other declarations in this file."
  (k/This))

(az/defn init TopLevelFields [[val :u32]]
  (az/object [[:foo val]
              [:bar (k/* val 10)]]))

(comment
  (init 42))
