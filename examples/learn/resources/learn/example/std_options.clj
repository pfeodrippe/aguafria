(ns learn.example.std-options
  (:require [aguafria.keyword :as ak]
            [aguafria.std.log :as log]
            [aguafria.zig :as az]))

(az/defn- myLogFn :void
  [[level {:attrs #{ak/comptime}} log/Level]
   [scope {:attrs #{ak/comptime}} (ak/EnumLiteral)]
   [format {:attrs #{ak/comptime}} [:slice-const :u8]]
   [arguments :anytype]]
  ;; A custom logger can replace this delegation to the default implementation.
  (log/defaultLog level scope format arguments))

(az/defconst std-options
  "Override standard-library behavior through std.Options."
  {:attrs #{ak/pub}}
  aguafria.std/Options
  {:enable_segfault_handler true
   :logFn myLogFn})
