(ns learn.example.TopLevelFields
  "Because this file contains fields, it is a type which is intended to be instantiated, and so\nis named in TitleCase instead of snake_case by convention."
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/deffield foo :u32)
(a/deffield bar :u64)

(a/defconst TopLevelFields
  "`@This()` can be used to refer to this struct type. In files with fields, it is quite common to
name the type here, so it can be easily referenced by other declarations in this file."
  (This))

(a/defn init TopLevelFields [[val :u32]]
  (a/object [[:foo val]
             [:bar (* val 10)]]))

(comment
  (init 42))
